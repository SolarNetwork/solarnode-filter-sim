/* ==================================================================
 * FilterCapability.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.domain;

/**
 * Capabilities a filter type can have, which affect how it behaves in a
 * simulation.
 *
 * @author matt
 * @version 1.0
 */
public enum FilterCapability {

	/** The filter can generate new datum, by adding them to the Datum Queue. */
	GeneratesDatum,

	/**
	 * The filter can issue instructions, for example to update a control value.
	 */
	Instructions,

	/**
	 * The filter relies on scheduled tasks, which the simulator does not run
	 * yet.
	 */
	ScheduledTasks,

}
