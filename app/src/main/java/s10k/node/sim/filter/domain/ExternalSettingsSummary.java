/* ==================================================================
 * ExternalSettingsSummary.java - 20 Oct 2026 10:09:00 am
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

/**
 * A summary of one external settings file.
 *
 * @param name
 *        the file name
 * @param settingCount
 *        the number of setting rows in the file, including {@code .FACTORY}
 *        rows
 * @param applied
 *        {@code true} if the file was applied, or {@code false} if SolarNode
 *        cannot import it, as described by an error message
 * @param filterIds
 *        the {@code id} of each filter the file has settings for
 * @param ignored
 *        the components of the file that are not filters
 * @param messages
 *        messages about the file
 * @author matt
 * @version 1.0
 */
public record ExternalSettingsSummary(String name, int settingCount, boolean applied,
		List<String> filterIds, List<IgnoredComponent> ignored, List<SimulationMessage> messages) {

}
