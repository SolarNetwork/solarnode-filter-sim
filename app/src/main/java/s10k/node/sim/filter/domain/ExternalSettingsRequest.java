/* ==================================================================
 * ExternalSettingsRequest.java - 20 Oct 2026 10:07:00 am
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

/**
 * A request to combine external settings CSV files.
 *
 * @param files
 *        the files, in any order
 * @author matt
 * @version 1.0
 */
public record ExternalSettingsRequest(@Nullable List<ExternalSettingsFile> files) {

	/**
	 * Get the files, never {@code null}.
	 *
	 * @return the files
	 */
	public List<ExternalSettingsFile> filesOrEmpty() {
		return (files != null ? files : List.of());
	}

}
