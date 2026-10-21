/* ==================================================================
 * InfoController.java - 17 Oct 2026 8:50:55 pm
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

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import s10k.node.sim.filter.custom.CustomFilterService;

/**
 * Web API for general application information.
 *
 * @author matt
 * @version 1.0
 */
@RestController
@RequestMapping("/api")
public class InfoController {

	/**
	 * The application name returned when build information is not available.
	 */
	public static final String DEFAULT_APP_NAME = "sn-filter-sim";

	private final @Nullable BuildProperties buildProperties;
	private final boolean customFilters;

	/**
	 * Constructor.
	 *
	 * @param buildProperties
	 *        the build properties, if available
	 * @param customFilters
	 *        the custom filters service, if available
	 */
	public InfoController(ObjectProvider<BuildProperties> buildProperties,
			ObjectProvider<CustomFilterService> customFilters) {
		super();
		this.buildProperties = buildProperties.getIfAvailable();
		final CustomFilterService custom = customFilters.getIfAvailable();
		this.customFilters = (custom != null && custom.isConfigured());
	}

	/**
	 * Application information.
	 *
	 * @param name
	 *        the application name
	 * @param version
	 *        the application version, or {@code null} if not known
	 * @param customFilters
	 *        {@code true} if the simulator was started with a custom filters
	 *        path, so the custom filters can be reloaded
	 */
	public record AppInfo(String name, @Nullable String version, boolean customFilters) {

	}

	/**
	 * Get the application information.
	 *
	 * @return the information
	 */
	@GetMapping("/info")
	public AppInfo info() {
		final BuildProperties props = this.buildProperties;
		if ( props == null ) {
			return new AppInfo(DEFAULT_APP_NAME, null, customFilters);
		}
		final String name = props.getName();
		return new AppInfo(name != null ? name : DEFAULT_APP_NAME, props.getVersion(), customFilters);
	}

}
