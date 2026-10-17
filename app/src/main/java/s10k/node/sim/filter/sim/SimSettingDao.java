/* ==================================================================
 * SimSettingDao.java - 18 Oct 2026 8:56:20 am
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
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.KeyValuePair;
import net.solarnetwork.node.dao.SettingDao;
import net.solarnetwork.node.domain.Setting;
import net.solarnetwork.node.domain.SettingNote;

/**
 * In-memory {@link SettingDao}.
 *
 * <p>
 * SolarNode's {@code DefaultOperationalModesService} persists active modes with
 * a {@code SettingDao}, and only changes modes when one is available, so the
 * simulation provides this one.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
@SuppressWarnings("JavaUtilDate")
public class SimSettingDao implements SettingDao {

	private final Clock clock;
	private final Map<String, Map<String, Setting>> settings = new TreeMap<>();

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the clock to use for modification dates
	 */
	public SimSettingDao(Clock clock) {
		super();
		this.clock = clock;
	}

	@Override
	public void storeSetting(String key, String value) {
		storeSetting(key, "", value);
	}

	@Override
	public void storeSetting(String key, String type, String value) {
		storeSetting(new Setting(key, type, value, null));
	}

	@Override
	public synchronized void storeSetting(Setting setting) {
		Setting s = new Setting(setting.getKey(), setting.getType() != null ? setting.getType() : "",
				setting.getValue(), setting.getNote(), setting.getFlags());
		s.setModified(Date.from(clock.instant()));
		settings.computeIfAbsent(s.getKey(), _ -> new TreeMap<>()).put(s.getType(), s);
	}

	@Override
	public synchronized @Nullable Setting readSetting(String key, String type) {
		Map<String, Setting> m = settings.get(key);
		return (m != null ? m.get(type != null ? type : "") : null);
	}

	@Override
	public @Nullable String getSetting(String key) {
		return getSetting(key, "");
	}

	@Override
	public @Nullable String getSetting(String key, String type) {
		Setting s = readSetting(key, type);
		return (s != null ? s.getValue() : null);
	}

	@Override
	public synchronized List<KeyValuePair> getSettingValues(String key) {
		List<KeyValuePair> result = new ArrayList<>(4);
		Map<String, Setting> m = settings.get(key);
		if ( m != null ) {
			for ( Setting s : m.values() ) {
				result.add(new KeyValuePair(s.getType(), s.getValue()));
			}
		}
		return result;
	}

	@Override
	public List<SettingNote> notesForKey(String key) {
		return List.of();
	}

	@Override
	public void storeNotes(Iterable<? extends SettingNote> notes) {
		// not supported
	}

	@Override
	public synchronized boolean deleteSetting(String key) {
		return (settings.remove(key) != null);
	}

	@Override
	public synchronized boolean deleteSetting(String key, @Nullable String type) {
		Map<String, Setting> m = settings.get(key);
		if ( m == null ) {
			return false;
		}
		boolean result = (m.remove(type != null ? type : "") != null);
		if ( m.isEmpty() ) {
			settings.remove(key);
		}
		return result;
	}

	@Override
	public @Nullable Date getSettingModificationDate(String key, String type) {
		Setting s = readSetting(key, type);
		return (s != null ? s.getModified() : null);
	}

	@Override
	public synchronized @Nullable Date getMostRecentModificationDate() {
		Date result = null;
		for ( Map<String, Setting> m : settings.values() ) {
			for ( Setting s : m.values() ) {
				if ( s.getModified() != null && (result == null || s.getModified().after(result)) ) {
					result = s.getModified();
				}
			}
		}
		return result;
	}

	@Override
	public synchronized BatchResult batchProcess(BatchCallback<Setting> callback, BatchOptions options) {
		int count = 0;
		outer: for ( Map<String, Setting> m : new ArrayList<>(settings.values()) ) {
			for ( Setting s : new ArrayList<>(m.values()) ) {
				BatchCallbackResult action = callback.handle(s);
				count++;
				if ( action == BatchCallbackResult.UPDATE
						|| action == BatchCallbackResult.UPDATE_STOP ) {
					storeSetting(s);
				} else if ( action == BatchCallbackResult.DELETE ) {
					deleteSetting(s.getKey(), s.getType());
				}
				if ( action == BatchCallbackResult.STOP || action == BatchCallbackResult.UPDATE_STOP ) {
					break outer;
				}
			}
		}
		final int processed = count;
		return () -> processed;
	}

}
