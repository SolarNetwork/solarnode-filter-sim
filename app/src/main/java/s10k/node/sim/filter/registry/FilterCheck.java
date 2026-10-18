/* ==================================================================
 * FilterCheck.java - 18 Oct 2026 7:10:12 pm
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

package s10k.node.sim.filter.registry;

import org.jspecify.annotations.Nullable;
import net.solarnetwork.service.DatumFilterService;

/**
 * API for checking a filter for a configuration problem that stops it from
 * working, which SolarNode only reports in its log.
 *
 * @author matt
 * @version 1.0
 */
@FunctionalInterface
public interface FilterCheck {

	/**
	 * Check a filter.
	 *
	 * @param filter
	 *        the filter, after its settings have been applied and it has been
	 *        started
	 * @param context
	 *        the simulated services
	 * @return a description of the problem, or {@code null} if there is none
	 */
	@Nullable
	String check(DatumFilterService filter, FilterContext context);

}
