/* ==================================================================
 * FilterFactory.java - 18 Oct 2026 8:56:20 am
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

import net.solarnetwork.service.DatumFilterService;

/**
 * API for creating a filter instance, the way the SolarNode blueprint wiring
 * creates it.
 *
 * @author matt
 * @version 1.0
 */
@FunctionalInterface
public interface FilterFactory {

	/**
	 * Create a new filter instance.
	 *
	 * @param context
	 *        the simulated services
	 * @return the new filter
	 */
	DatumFilterService create(FilterContext context);

}
