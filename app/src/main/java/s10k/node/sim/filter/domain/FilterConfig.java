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
 * <p>
 * A filter with the {@link #UNKNOWN_FACTORY_UID} factory UID is known only by
 * its {@code uid} setting: a chain refers to it, but its settings are not part
 * of the configuration, such as when a settings CSV file holds a chain but not
 * all of its filters. Datum pass through it unchanged, and it is exported only
 * as the chain's reference to its Service Name.
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
 * @param comment
 *        the comment lines that introduce the filter in a settings CSV file,
 *        each a {@code #} comment or a blank row, separated by line feeds, or
 *        {@code null}
 * @param notes
 *        notes about individual settings, as SolarNode setting keys and the
 *        values of their settings CSV note column, or {@code null}
 * @author matt
 * @version 1.0
 */
public record FilterConfig(String id, String factoryUid, @Nullable String instanceKey,
		@Nullable Map<String, String> settings, @Nullable List<String> filterIds,
		@Nullable String comment, @Nullable Map<String, String> notes) {

	/** The factory UID of a filter that is known only by its Service Name. */
	public static final String UNKNOWN_FACTORY_UID = "unknown";

	/**
	 * Constructor, without a comment or notes.
	 *
	 * @param id
	 *        a unique identifier for this configuration, assigned by the client
	 * @param factoryUid
	 *        the SolarNode component factory UID of the filter type
	 * @param instanceKey
	 *        the SolarNode component instance key, or {@code null}
	 * @param settings
	 *        the filter settings, or {@code null}
	 * @param filterIds
	 *        for a filter chain, the {@code id} values of its member filters,
	 *        or {@code null}
	 */
	public FilterConfig(String id, String factoryUid, @Nullable String instanceKey,
			@Nullable Map<String, String> settings, @Nullable List<String> filterIds) {
		this(id, factoryUid, instanceKey, settings, filterIds, null, null);
	}

	/**
	 * Test if this is an unknown filter, known only by its Service Name.
	 *
	 * @return {@code true} if the factory UID is {@link #UNKNOWN_FACTORY_UID}
	 */
	public boolean isUnknown() {
		return UNKNOWN_FACTORY_UID.equals(factoryUid);
	}

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

	/**
	 * Get the setting notes, never {@code null}.
	 *
	 * @return the notes
	 */
	public Map<String, String> notesOrEmpty() {
		return (notes != null ? notes : Map.of());
	}

}
