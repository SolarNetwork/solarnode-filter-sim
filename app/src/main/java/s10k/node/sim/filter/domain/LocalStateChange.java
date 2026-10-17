/* ==================================================================
 * LocalStateChange.java - 18 Oct 2026 8:56:20 am
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
 * A change to one Local State entry.
 *
 * @param key
 *        the Local State key
 * @param type
 *        the Local State type name, or {@code null} if the entry was deleted
 * @param value
 *        the new value, or {@code null} if the entry was deleted
 * @param previousValue
 *        the previous value, or {@code null} if the entry did not exist
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LocalStateChange(String key, @Nullable String type, @Nullable Object value,
		@Nullable Object previousValue) {

}
