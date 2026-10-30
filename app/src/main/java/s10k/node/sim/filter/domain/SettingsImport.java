/* ==================================================================
 * SettingsImport.java - 18 Oct 2026 12:59:19 pm
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

import java.util.List;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The result of importing a SolarNode settings CSV file.
 *
 * @param config
 *        the filter configuration from the file
 * @param messages
 *        messages about the import, such as settings that SolarNode would skip
 * @param ignored
 *        the components with settings that are not filter settings, which are
 *        ignored
 * @param controls
 *        the simulated controls from the file's Mock, Modbus, and BACnet
 *        control settings, for the simulation's environment, or {@code null} if
 *        there are none; the export never includes them
 * @param conductors
 *        the Control Conductors from the file's settings, for the simulation's
 *        environment, or {@code null} if there are none; the export never
 *        includes them
 * @param datumSources
 *        the datum sources from the file's settings of the supported datum
 *        source types, for the simulation, or {@code null} if there are none;
 *        the export never includes them
 * @param invokers
 *        the Operational Mode Datum Source Invokers from the file's settings,
 *        for the simulation, or {@code null} if there are none; the export
 *        never includes them
 * @author matt
 * @version 1.3
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettingsImport(SimConfig config, List<SimulationMessage> messages,
		List<IgnoredComponent> ignored, @Nullable List<NodeControlConfig> controls,
		@Nullable List<ControlConductorConfig> conductors,
		@Nullable List<DatumSourceConfig> datumSources, @Nullable List<OpModeInvokerConfig> invokers) {

}
