/* ==================================================================
 * ControlKind.java - 29 Oct 2026 4:05:00 pm
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
 * The kinds of simulated control.
 *
 * @author matt
 * @version 1.0
 */
public enum ControlKind {

	/**
	 * A control whose value instructions set, like SolarNode's Mock control.
	 */
	Mock,

	/**
	 * SolarNode's Operational Mode Switch, a {@code Boolean} control whose
	 * value is whether its operational mode is active: setting it enables or
	 * disables the mode.
	 */
	OperationalModeSwitch,

}
