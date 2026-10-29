/* ==================================================================
 * DatumSourceTypesController.java - 29 Oct 2026 9:20:00 pm
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import s10k.node.sim.filter.domain.DatumSourceTypeInfo;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;

/**
 * Web API for the supported datum source types.
 *
 * @author matt
 * @version 1.0
 */
@RestController
@RequestMapping("/api")
public class DatumSourceTypesController {

	private final DatumSourceTypeRegistry registry;

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the datum source type registry
	 */
	public DatumSourceTypesController(DatumSourceTypeRegistry registry) {
		super();
		this.registry = registry;
	}

	/**
	 * Get the supported datum source types.
	 *
	 * @param locale
	 *        the request locale
	 * @return the datum source types, sorted by name
	 */
	@GetMapping("/datum-source-types")
	public List<DatumSourceTypeInfo> datumSourceTypes(Locale locale) {
		return registry.datumSourceTypes(locale);
	}

}
