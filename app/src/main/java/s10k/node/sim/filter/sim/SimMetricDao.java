/* ==================================================================
 * SimMetricDao.java - 20 Oct 2026 3:35:26 pm
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DuplicateKeyException;
import net.solarnetwork.dao.BasicFilterResults;
import net.solarnetwork.dao.FilterResults;
import net.solarnetwork.domain.SortDescriptor;
import net.solarnetwork.node.metrics.dao.MetricDao;
import net.solarnetwork.node.metrics.dao.MetricFilter;
import net.solarnetwork.node.metrics.domain.Metric;
import net.solarnetwork.node.metrics.domain.MetricKey;

/**
 * In-memory {@link MetricDao}.
 *
 * <p>
 * Like SolarNode's {@code JdbcMetricDao}, saving a metric whose date, type, and
 * name are already stored throws a {@link DuplicateKeyException}. Metrics are
 * kept in key order: by date, then type, then name. Filtering supports the
 * type, name, and date criteria, but not aggregates.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimMetricDao implements MetricDao {

	private final Map<MetricKey, Metric> metrics = new TreeMap<>();
	private @Nullable Consumer<Metric> listener;

	@Override
	public Class<? extends Metric> getObjectType() {
		return Metric.class;
	}

	@Override
	public synchronized MetricKey save(Metric entity) {
		final MetricKey key = entity.getId();
		if ( key == null ) {
			throw new IllegalArgumentException("The metric has no key.");
		}
		if ( metrics.containsKey(key) ) {
			throw new DuplicateKeyException("The metric %s already exists.".formatted(key));
		}
		metrics.put(key, new Metric(entity));
		final Consumer<Metric> l = this.listener;
		if ( l != null ) {
			l.accept(entity);
		}
		return key;
	}

	@Override
	public synchronized @Nullable Metric get(MetricKey id) {
		final Metric m = metrics.get(id);
		return (m != null ? new Metric(m) : null);
	}

	@Override
	public synchronized Collection<Metric> getAll(@Nullable List<SortDescriptor> sorts) {
		final List<Metric> result = new ArrayList<>(metrics.size());
		for ( Metric m : metrics.values() ) {
			result.add(new Metric(m));
		}
		return result;
	}

	@Override
	public synchronized void delete(Metric entity) {
		final MetricKey key = entity.getId();
		if ( key != null ) {
			metrics.remove(key);
		}
	}

	@Override
	public synchronized FilterResults<Metric, MetricKey> findFiltered(MetricFilter filter,
			@Nullable List<SortDescriptor> sorts, @Nullable Long offset, @Nullable Integer max) {
		if ( filter.hasAggregateCriteria() ) {
			throw new UnsupportedOperationException("Metric aggregates are not supported.");
		}
		final List<Metric> result = new ArrayList<>(8);
		for ( Metric m : metrics.values() ) {
			if ( matches(filter, m) ) {
				result.add(new Metric(m));
			}
		}
		final int start = (int) Math.min(result.size(), offset != null ? Math.max(0, offset) : 0);
		final int end = (max != null && max > 0 ? Math.min(result.size(), start + max) : result.size());
		final List<Metric> page = result.subList(start, end);
		return new BasicFilterResults<>(page, (long) result.size(), start, page.size());
	}

	@Override
	public synchronized int deleteFiltered(MetricFilter filter) {
		int count = 0;
		for ( Iterator<Metric> itr = metrics.values().iterator(); itr.hasNext(); ) {
			if ( matches(filter, itr.next()) ) {
				itr.remove();
				count++;
			}
		}
		return count;
	}

	@Override
	public BatchResult batchProcess(BatchCallback<Metric> callback, BatchOptions options) {
		throw new UnsupportedOperationException("Batch processing is not supported.");
	}

	private static boolean matches(MetricFilter filter, Metric m) {
		final String[] types = filter.getTypes();
		if ( types != null && types.length > 0 && !Arrays.asList(types).contains(m.getType()) ) {
			return false;
		}
		final String[] names = filter.getNames();
		if ( names != null && names.length > 0 && !Arrays.asList(names).contains(m.getName()) ) {
			return false;
		}
		final Instant ts = m.getTimestamp();
		final Instant start = filter.getStartDate();
		if ( start != null && ts.isBefore(start) ) {
			return false;
		}
		final Instant end = filter.getEndDate();
		return (end == null || ts.isBefore(end));
	}

	/**
	 * Set the listener for saved metrics.
	 *
	 * @param listener
	 *        the listener to set, or {@code null}
	 */
	public void setListener(@Nullable Consumer<Metric> listener) {
		this.listener = listener;
	}

}
