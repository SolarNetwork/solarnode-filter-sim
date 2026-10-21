/* ==================================================================
 * FilterBundleInfo.java - 21 Oct 2026 6:40:15 pm
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
 * The bundle a custom filter type comes from.
 *
 * @param name
 *        the bundle symbolic name, or the jar file name if the bundle has none
 * @param version
 *        the bundle version, or {@code null} if the bundle has none
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FilterBundleInfo(String name, @Nullable String version) {

	/**
	 * Get the bundle name and version, for messages.
	 *
	 * @return the label, for example {@code com.example.filter 1.2.0}
	 */
	public String label() {
		return (version != null ? name + " " + version : name);
	}

}
