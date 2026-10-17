/* ==================================================================
 * SimLocalStateDao.java - 18 Oct 2026 8:56:20 am
 *
 * Copyright 2026 SolarNetwork.net Dev Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ==================================================================
 */

package s10k.node.sim.filter.sim;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.dao.BatchableDao;
import net.solarnetwork.domain.SortDescriptor;
import net.solarnetwork.node.dao.LocalStateDao;
import net.solarnetwork.node.domain.LocalState;
import net.solarnetwork.node.domain.LocalStateType;

/**
 * In-memory {@link LocalStateDao}, with the same semantics as SolarNode's
 * {@code JdbcLocalStateDao}.
 *
 * <p>
 * Like the JDBC DAO, entities are stored by their type and encoded data, and
 * read back by decoding the data. This matters because SolarNode's Local State
 * CSV import creates entities with the raw CSV string as their value: reading
 * them back gives a value of the entity's type, such as a {@code Long} for
 * {@code Int64}. Values are compared by their encoded data, like the JDBC
 * {@code MERGE} statements do.
 * </p>
 *
 * <p>
 * Times come from the given clock, which in a simulation follows the datum
 * being processed.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimLocalStateDao implements LocalStateDao {

	/**
	 * API for observing Local State changes.
	 */
	@FunctionalInterface
	public interface ChangeListener {

		/**
		 * Handle a change.
		 *
		 * @param key
		 *        the key of the entity that changed
		 * @param previous
		 *        the previous entity, or {@code null} if it was created
		 * @param current
		 *        the current entity, or {@code null} if it was deleted
		 */
		void localStateChanged(String key, @Nullable LocalState previous, @Nullable LocalState current);

	}

	private final Clock clock;
	private final Map<String, LocalState> states = new TreeMap<>();
	private @Nullable ChangeListener changeListener;

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the clock to use for created and modified dates
	 */
	public SimLocalStateDao(Clock clock) {
		super();
		this.clock = clock;
	}

	@Override
	public Class<? extends LocalState> getObjectType() {
		return LocalState.class;
	}

	@Override
	public synchronized String save(LocalState entity) {
		final String key = entity.getKey();
		final LocalState prev = states.get(key);
		final Instant now = now();
		final Instant created = (entity.getCreated() != null ? entity.getCreated()
				: prev != null && prev.getCreated() != null ? prev.getCreated() : now);
		final Instant modified = (entity.getModified() != null ? entity.getModified() : now);
		store(prev, stored(key, created, modified, entity.getType(), entity.getData()));
		return key;
	}

	@Override
	public synchronized @Nullable LocalState get(String id) {
		LocalState s = states.get(id);
		return (s != null ? copy(s) : null);
	}

	@Override
	public synchronized Collection<LocalState> getAll(@Nullable List<SortDescriptor> sorts) {
		List<LocalState> result = new ArrayList<>(states.size());
		for ( LocalState s : states.values() ) {
			result.add(copy(s));
		}
		return result;
	}

	@Override
	public synchronized void delete(LocalState entity) {
		final LocalState prev = states.remove(entity.getKey());
		if ( prev != null ) {
			notifyListener(entity.getKey(), prev, null);
		}
	}

	@Override
	public synchronized int deleteAll() {
		final List<LocalState> all = new ArrayList<>(states.values());
		states.clear();
		for ( LocalState s : all ) {
			notifyListener(s.getKey(), s, null);
		}
		return all.size();
	}

	@Override
	public synchronized LocalState compareAndSave(LocalState entity, @Nullable Object expectedValue) {
		// MERGE: when matched and stored data <> expected data, leave unchanged
		final LocalState prev = states.get(entity.getKey());
		final byte[] expected = entity.getType().encode(expectedValue);
		if ( prev != null && prev.getData() != null && expected != null
				&& !Arrays.equals(prev.getData(), expected) ) {
			return copy(prev);
		}
		return copy(upsertCopy(prev, entity));
	}

	@Override
	public synchronized LocalState compareAndChange(LocalState entity) {
		// MERGE: when matched and stored data = new data, leave unchanged
		final LocalState prev = states.get(entity.getKey());
		if ( prev != null && sameData(prev, entity) ) {
			return copy(prev);
		}
		return copy(upsertCopy(prev, entity));
	}

	@Override
	public synchronized @Nullable LocalState getAndSave(LocalState entity) {
		// MERGE: when matched and stored data = new data, leave unchanged; return previous
		final LocalState prev = states.get(entity.getKey());
		if ( prev != null && sameData(prev, entity) ) {
			return copy(prev);
		}
		final Instant now = now();
		final Instant created = (entity.getCreated() != null ? entity.getCreated() : now);
		final Instant modified = (entity.getModified() != null ? entity.getModified()
				: entity.getCreated() == null ? created : now);
		store(prev, stored(entity.getKey(), prev != null ? prev.getCreated() : created, modified,
				entity.getType(), entity.getData()));
		return (prev != null ? copy(prev) : null);
	}

	@Override
	public synchronized BatchResult batchProcess(BatchCallback<LocalState> callback,
			BatchOptions options) {
		int count = 0;
		for ( LocalState s : new ArrayList<>(states.values()) ) {
			LocalState c = copy(s);
			BatchableDao.BatchCallbackResult action = callback.handle(c);
			count++;
			switch (action) {
				case UPDATE, UPDATE_STOP -> save(c);
				case DELETE -> delete(c);
				default -> {
					// nothing to do
				}
			}
			if ( action == BatchCallbackResult.STOP || action == BatchCallbackResult.UPDATE_STOP ) {
				break;
			}
		}
		final int processed = count;
		return () -> processed;
	}

	/**
	 * Set a listener for changes.
	 *
	 * @param changeListener
	 *        the listener to set, or {@code null}
	 */
	public void setChangeListener(@Nullable ChangeListener changeListener) {
		this.changeListener = changeListener;
	}

	/**
	 * Store a copy of an entity, like the JDBC DAO's {@code copyState()} and
	 * {@code MERGE}: the creation date is kept if the entity exists, and the
	 * modification date is set to now.
	 */
	private LocalState upsertCopy(@Nullable LocalState prev, LocalState entity) {
		final Instant now = now();
		final Instant created = (prev != null && prev.getCreated() != null ? prev.getCreated()
				: entity.getCreated() != null ? entity.getCreated() : now);
		final LocalState s = stored(entity.getKey(), created, now, entity.getType(), entity.getData());
		store(prev, s);
		return s;
	}

	private void store(@Nullable LocalState prev, LocalState s) {
		states.put(s.getKey(), s);
		if ( prev == null || !sameData(prev, s) || prev.getType() != s.getType() ) {
			notifyListener(s.getKey(), prev, s);
		}
	}

	private void notifyListener(String key, @Nullable LocalState prev, @Nullable LocalState current) {
		final ChangeListener l = this.changeListener;
		if ( l != null ) {
			l.localStateChanged(key, prev != null ? copy(prev) : null,
					current != null ? copy(current) : null);
		}
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

	private static boolean sameData(LocalState a, LocalState b) {
		// SQL comparison: a NULL value never equals anything
		return a.getData() != null && b.getData() != null && Arrays.equals(a.getData(), b.getData());
	}

	private static LocalState stored(String key, @Nullable Instant created, @Nullable Instant modified,
			LocalStateType type, byte @Nullable [] data) {
		LocalState s = new LocalState(key, created, type);
		s.setModified(modified);
		s.setData(data != null ? data.clone() : null);
		return s;
	}

	private static LocalState copy(LocalState s) {
		return stored(s.getKey(), s.getCreated(), s.getModified(), s.getType(), s.getData());
	}

}
