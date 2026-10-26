/* ==================================================================
 * ControlPublishMode.java - 25 Oct 2026 10:12:40 am
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
 * When a simulated control publishes a datum of its value to the Datum Queue,
 * like a SolarNode Control Datum Source does for each "control changed" event.
 *
 * <p>
 * SolarNode controls post that event differently: the Mock control only when
 * its value changes, and the Modbus control after every successful write, even
 * of the same value.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public enum ControlPublishMode {

	/** Never publish a datum. */
	Never,

	/**
	 * Publish a datum when a set changes the value, like the Mock control.
	 */
	Change,

	/**
	 * Publish a datum after every successful set, even of the same value, like
	 * the Modbus control.
	 */
	Update,

}
