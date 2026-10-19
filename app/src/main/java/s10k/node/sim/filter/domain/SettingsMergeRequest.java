/* ==================================================================
 * SettingsMergeRequest.java - 20 Oct 2026 10:05:00 am
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

import org.jspecify.annotations.Nullable;

/**
 * A request to add the settings of a settings CSV file to a filter
 * configuration, the way SolarNode imports a file into its existing settings.
 *
 * @param config
 *        the configuration to add the settings to
 * @param csv
 *        the settings CSV file content
 * @author matt
 * @version 1.0
 */
public record SettingsMergeRequest(@Nullable SimConfig config, @Nullable String csv) {

}
