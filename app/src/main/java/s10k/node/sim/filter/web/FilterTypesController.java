/* ==================================================================
 * FilterTypesController.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.web;

import java.util.List;
import java.util.Locale;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import s10k.node.sim.filter.custom.CustomFilterService;
import s10k.node.sim.filter.custom.CustomFilters;
import s10k.node.sim.filter.domain.CustomFiltersReload;
import s10k.node.sim.filter.domain.FilterTypeInfo;
import s10k.node.sim.filter.domain.UnavailableFilterTypeInfo;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.UnavailableFilterType;
import s10k.node.sim.filter.sim.SimulationException;

/**
 * Web API for the supported filter types.
 *
 * @author matt
 * @version 1.0
 */
@RestController
@RequestMapping("/api")
public class FilterTypesController {

	private final FilterTypeRegistry registry;
	private final CustomFilterService customFilters;

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param customFilters
	 *        the custom filters service
	 */
	public FilterTypesController(FilterTypeRegistry registry, CustomFilterService customFilters) {
		super();
		this.registry = registry;
		this.customFilters = customFilters;
	}

	/**
	 * Get the supported filter types.
	 *
	 * @param locale
	 *        the request locale
	 * @return the filter types, sorted by name
	 */
	@GetMapping("/filter-types")
	public List<FilterTypeInfo> filterTypes(Locale locale) {
		return registry.filterTypes(locale);
	}

	/**
	 * Get the filter types the simulator knows of but cannot run, such as
	 * custom filter types that need a service the simulator does not provide.
	 *
	 * @return the filter types, sorted by name
	 */
	@GetMapping("/filter-types/unavailable")
	public List<UnavailableFilterTypeInfo> unavailableFilterTypes() {
		return registry.unavailableTypes().stream().map(UnavailableFilterType::info).toList();
	}

	/**
	 * Load the custom filters again, so a rebuilt bundle can be tried without
	 * restarting the simulator.
	 *
	 * <p>
	 * The jars and directories are those of the {@code sim.filters.path} the
	 * simulator was started with. The description of the custom filters is also
	 * printed, like the startup message.
	 * </p>
	 *
	 * @param locale
	 *        the request locale
	 * @return the filter types after the reload
	 * @throws SimulationException
	 *         if the simulator was not started with a custom filters path
	 */
	@PostMapping("/filter-types/reload")
	public CustomFiltersReload reload(Locale locale) {
		final CustomFilters custom;
		try {
			custom = customFilters.reload();
		} catch ( IllegalStateException e ) {
			throw new SimulationException(String.valueOf(e.getMessage()));
		}
		System.out.println();
		for ( String line : custom.describe(Locale.getDefault()) ) {
			System.out.println(line);
		}
		return new CustomFiltersReload(registry.filterTypes(locale), unavailableFilterTypes(),
				custom.describe(locale).stream().map(String::strip).toList());
	}

}
