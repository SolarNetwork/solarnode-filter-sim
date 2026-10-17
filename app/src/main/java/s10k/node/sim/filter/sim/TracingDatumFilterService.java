/* ==================================================================
 * TracingDatumFilterService.java - 18 Oct 2026 8:56:20 am
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

import java.util.Map;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.service.DatumFilterService;

/**
 * A filter decorator that records every invocation with a {@link FilterTracer}.
 *
 * <p>
 * Filter chains find their members by {@code uid}, which this class passes
 * through, so chains can be given these decorators as their available filters.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class TracingDatumFilterService implements DatumFilterService {

	private final String filterId;
	private final String factoryUid;
	private final DatumFilterService delegate;
	private final boolean chain;
	private final FilterTracer tracer;

	/**
	 * Constructor.
	 *
	 * @param filterId
	 *        the filter configuration {@code id}
	 * @param factoryUid
	 *        the filter factory UID
	 * @param delegate
	 *        the filter
	 * @param chain
	 *        {@code true} if the filter is a filter chain
	 * @param tracer
	 *        the tracer
	 */
	public TracingDatumFilterService(String filterId, String factoryUid, DatumFilterService delegate,
			boolean chain, FilterTracer tracer) {
		super();
		this.filterId = filterId;
		this.factoryUid = factoryUid;
		this.delegate = delegate;
		this.chain = chain;
		this.tracer = tracer;
	}

	@Override
	public @Nullable DatumSamplesOperations filter(Datum datum, DatumSamplesOperations samples,
			@Nullable Map<String, Object> parameters) {
		return tracer.invoke(this, datum, samples, parameters);
	}

	@Override
	public @Nullable String getUid() {
		return delegate.getUid();
	}

	@Override
	public @Nullable String getGroupUid() {
		return delegate.getGroupUid();
	}

	@Override
	public @Nullable String getDisplayName() {
		return delegate.getDisplayName();
	}

	@Override
	public String getDescription() {
		return delegate.getDescription();
	}

	/**
	 * Get the filter configuration {@code id}.
	 *
	 * @return the ID
	 */
	public String filterId() {
		return filterId;
	}

	/**
	 * Get the filter factory UID.
	 *
	 * @return the factory UID
	 */
	public String factoryUid() {
		return factoryUid;
	}

	/**
	 * Get the decorated filter.
	 *
	 * @return the filter
	 */
	public DatumFilterService delegate() {
		return delegate;
	}

	/**
	 * Test if the filter is a filter chain.
	 *
	 * @return {@code true} if the filter is a filter chain
	 */
	public boolean isChain() {
		return chain;
	}

}
