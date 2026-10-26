/* ==================================================================
 * ControlConductorConfig.java - 25 Oct 2026 11:05:12 am
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

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A simulated SolarNode Control Conductor, which schedules control changes when
 * it handles an {@code OrchestrateControls} instruction.
 *
 * @param settings
 *        the conductor's SolarNode settings, as for the
 *        {@code net.solarnetwork.node.control.conductor} component, for example
 *        {@code uid}, {@code predicate.expression}, {@code taskConfigsCount},
 *        and {@code taskConfigs[0].controlId}
 * @author matt
 * @version 1.0
 */
public record ControlConductorConfig(@Nullable Map<String, String> settings) {

	/**
	 * Get the settings.
	 *
	 * @return the settings, never {@code null}
	 */
	public Map<String, String> settingsOrEmpty() {
		return (settings != null ? settings : Map.of());
	}

}
