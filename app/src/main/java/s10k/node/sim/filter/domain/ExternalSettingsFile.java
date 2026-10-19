/* ==================================================================
 * ExternalSettingsFile.java - 20 Oct 2026 10:06:00 am
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
 * An external settings CSV file, like one in a SolarNode
 * {@code auto-settings.d} directory.
 *
 * @param name
 *        the file name, which sets the order files are applied in
 * @param csv
 *        the settings CSV file content
 * @author matt
 * @version 1.0
 */
public record ExternalSettingsFile(@Nullable String name, @Nullable String csv) {

}
