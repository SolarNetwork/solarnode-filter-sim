/* ==================================================================
 * SettingsFormRequest.java - 18 Oct 2026 12:25:01 pm
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

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A request for the settings form of a filter.
 *
 * @param factoryUid
 *        the SolarNode component factory UID of the filter type
 * @param settings
 *        the filter settings, as SolarNode setting keys and values
 * @author matt
 * @version 1.0
 */
public record SettingsFormRequest(String factoryUid, @Nullable Map<String, String> settings) {

	/**
	 * Get the settings, never {@code null}.
	 *
	 * @return the settings
	 */
	public Map<String, String> settingsOrEmpty() {
		return (settings != null ? settings : Map.of());
	}

}
