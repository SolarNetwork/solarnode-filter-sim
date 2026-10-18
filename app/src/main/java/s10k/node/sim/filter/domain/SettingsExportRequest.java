/* ==================================================================
 * SettingsExportRequest.java - 18 Oct 2026 12:59:19 pm
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
 * A request to export a filter configuration as a SolarNode settings CSV file.
 *
 * @param config
 *        the filter configuration
 * @param style
 *        the file style, or {@code null} for {@link Style#Organized}
 * @author matt
 * @version 1.0
 */
public record SettingsExportRequest(SimConfig config, @Nullable Style style) {

	/**
	 * A settings CSV file style.
	 */
	public enum Style {

		/**
		 * Sections in execution order, starting with the Datum Queue and the
		 * Main chain, each introduced by its comment lines or a generated
		 * banner.
		 */
		Organized,

		/** Rows sorted by key, without comments, like a SolarNode export. */
		Plain,

	}

	/**
	 * Get the style, never {@code null}.
	 *
	 * @return the style
	 */
	public Style styleOrDefault() {
		return (style != null ? style : Style.Organized);
	}

}
