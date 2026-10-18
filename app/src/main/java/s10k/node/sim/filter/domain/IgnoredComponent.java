/* ==================================================================
 * IgnoredComponent.java - 18 Oct 2026 12:59:19 pm
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
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A component in a settings CSV file whose settings are not filter settings.
 *
 * @param key
 *        the component key: a factory UID, or the setting key of a component
 *        that is not a factory instance
 * @param instanceKey
 *        the factory instance key, or {@code null} if the component is not a
 *        factory instance
 * @param settingCount
 *        the number of rows for the component in the file
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IgnoredComponent(String key, @Nullable String instanceKey, int settingCount) {

}
