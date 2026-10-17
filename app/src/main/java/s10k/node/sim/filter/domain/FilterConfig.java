/* ==================================================================
 * FilterConfig.java - 18 Oct 2026 8:56:20 am
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

/**
 * The configuration of one filter instance.
 *
 * <p>
 * A filter chain lists its members in {@code filterIds}, by their configuration
 * {@code id}. The simulator turns these into the chain's {@code transformUids}
 * setting, using each member's current {@code uid} (Service Name) setting, so
 * any {@code transformUids} values in {@code settings} are ignored.
 * </p>
 *
 * @param id
 *        a unique identifier for this configuration, assigned by the client
 * @param factoryUid
 *        the SolarNode component factory UID of the filter type
 * @param instanceKey
 *        the SolarNode component instance key, used in settings CSV files, or
 *        {@code null} if not assigned yet
 * @param settings
 *        the filter settings, as SolarNode setting keys and values
 * @param filterIds
 *        for a filter chain, the {@code id} values of its member filters, in
 *        execution order
 * @author matt
 * @version 1.0
 */
public record FilterConfig(String id, String factoryUid, @Nullable String instanceKey,
		@Nullable Map<String, String> settings, @Nullable List<String> filterIds) {

	/**
	 * Get the settings, never {@code null}.
	 *
	 * @return the settings
	 */
	public Map<String, String> settingsOrEmpty() {
		return (settings != null ? settings : Map.of());
	}

	/**
	 * Get the member filter IDs, never {@code null}.
	 *
	 * @return the member filter IDs
	 */
	public List<String> filterIdsOrEmpty() {
		return (filterIds != null ? filterIds : List.of());
	}

}
