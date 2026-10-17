/* ==================================================================
 * SettingsFormController.java - 18 Oct 2026 12:25:01 pm
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

import java.util.Locale;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import s10k.node.sim.filter.domain.SettingsForm;
import s10k.node.sim.filter.domain.SettingsFormRequest;
import s10k.node.sim.filter.form.SettingsFormService;

/**
 * Web API for filter settings forms.
 *
 * @author matt
 * @version 1.0
 */
@RestController
@RequestMapping("/api")
public class SettingsFormController {

	private final SettingsFormService service;

	/**
	 * Constructor.
	 *
	 * @param service
	 *        the settings form service
	 */
	public SettingsFormController(SettingsFormService service) {
		super();
		this.service = service;
	}

	/**
	 * Get the settings form of a filter.
	 *
	 * <p>
	 * After a change to a dynamic list's item count, the client requests the
	 * form again to get the changed list's fields, like the SolarNode setup UI
	 * does.
	 * </p>
	 *
	 * @param request
	 *        the request
	 * @param locale
	 *        the request locale
	 * @return the form
	 */
	@PostMapping("/settings-form")
	public SettingsForm settingsForm(@RequestBody SettingsFormRequest request, Locale locale) {
		return service.form(request, locale);
	}

}
