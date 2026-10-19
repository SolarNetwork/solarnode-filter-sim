/* ==================================================================
 * ExternalSettings.java - 20 Oct 2026 10:10:00 am
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
import java.util.Map;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * External settings files, combined the way SolarNode applies its auto settings
 * files.
 *
 * @param config
 *        the filter configuration the files define together
 * @param files
 *        a summary of each file, in the order they are applied
 * @param origins
 *        where each filter's settings come from, by filter {@code id}
 * @param datumQueueFile
 *        the name of the file with the Datum Queue setting, or {@code null} if
 *        none has one
 * @param messages
 *        messages about the combined configuration
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ExternalSettings(SimConfig config, List<ExternalSettingsSummary> files,
		Map<String, ExternalFilterOrigin> origins, @Nullable String datumQueueFile,
		List<SimulationMessage> messages) {

}
