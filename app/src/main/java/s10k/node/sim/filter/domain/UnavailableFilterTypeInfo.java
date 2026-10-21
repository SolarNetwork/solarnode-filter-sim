/* ==================================================================
 * UnavailableFilterTypeInfo.java - 21 Oct 2026 6:40:15 pm
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
 * Information about a filter type the simulator knows of but cannot run, such
 * as a custom filter type that needs a service the simulator does not provide.
 *
 * @param factoryUid
 *        the factory UID
 * @param name
 *        the display name, or the factory UID if it is not known
 * @param bundle
 *        the bundle of a custom filter type, or {@code null}
 * @param reason
 *        why the filter type is not available, as a sentence
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnavailableFilterTypeInfo(String factoryUid, String name,
		@Nullable FilterBundleInfo bundle, String reason) {

}
