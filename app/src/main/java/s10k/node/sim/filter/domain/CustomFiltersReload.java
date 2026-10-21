/* ==================================================================
 * CustomFiltersReload.java - 22 Oct 2026 9:05:12 am
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

/**
 * The result of reloading the custom filters.
 *
 * @param filterTypes
 *        the supported filter types, sorted by name
 * @param unavailable
 *        the filter types the simulator knows of but cannot run, sorted by name
 * @param messages
 *        the description of the custom filters, like the startup message's: the
 *        jars and their filter types, warnings, the types that are not
 *        available, and problems
 * @author matt
 * @version 1.0
 */
public record CustomFiltersReload(List<FilterTypeInfo> filterTypes,
		List<UnavailableFilterTypeInfo> unavailable, List<String> messages) {

}
