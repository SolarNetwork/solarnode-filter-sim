/* ==================================================================
 * SimulationObserver.java - 29 Oct 2026 8:10:00 pm
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

import s10k.node.sim.filter.domain.DatumTrace;

/**
 * Watches a simulation as it runs: it gets each datum's trace once the datum is
 * processed, so a run need not keep them, and can cancel the run.
 *
 * <p>
 * The methods are called on the thread running the simulation.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public interface SimulationObserver {

	/**
	 * Called when the simulation starts processing datum.
	 *
	 * @param expected
	 *        the number of datum expected: the input datum plus each datum
	 *        source's polls by the end, without the datum filters generate
	 */
	default void started(int expected) {
		// nothing
	}

	/**
	 * Called once a datum is processed.
	 *
	 * @param trace
	 *        the datum's trace, which does not change after this
	 */
	void processed(DatumTrace trace);

	/**
	 * Test if the simulation should stop, returning what it has so far.
	 *
	 * @return {@code true} to stop
	 */
	default boolean cancelled() {
		return false;
	}

}
