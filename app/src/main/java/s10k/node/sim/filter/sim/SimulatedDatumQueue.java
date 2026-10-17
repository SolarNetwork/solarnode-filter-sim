/* ==================================================================
 * SimulatedDatumQueue.java - 18 Oct 2026 8:56:20 am
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

import java.util.function.Consumer;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.service.DatumQueue;

/**
 * The Datum Queue given to filters that generate datum, which passes offered
 * datum to the simulation.
 *
 * @author matt
 * @version 1.0
 */
public class SimulatedDatumQueue implements DatumQueue {

	/**
	 * API for accepting offered datum.
	 */
	@FunctionalInterface
	public interface Sink {

		/**
		 * Accept a datum.
		 *
		 * @param datum
		 *        the datum
		 * @param persist
		 *        {@code true} to persist the datum after filtering
		 * @return {@code true} if the datum was accepted
		 */
		boolean offer(NodeDatum datum, boolean persist);

	}

	private final Sink sink;

	/**
	 * Constructor.
	 *
	 * @param sink
	 *        the sink for offered datum
	 */
	public SimulatedDatumQueue(Sink sink) {
		super();
		this.sink = sink;
	}

	@Override
	public boolean offer(NodeDatum datum, boolean persist) {
		if ( datum == null || datum.getSourceId() == null ) {
			return false;
		}
		return sink.offer(datum, persist);
	}

	@Override
	public void addConsumer(Consumer<NodeDatum> consumer) {
		// not supported
	}

	@Override
	public void removeConsumer(Consumer<NodeDatum> consumer) {
		// not supported
	}

}
