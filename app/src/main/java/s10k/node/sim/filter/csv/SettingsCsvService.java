/* ==================================================================
 * SettingsCsvService.java - 18 Oct 2026 1:03:29 pm
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

package s10k.node.sim.filter.csv;

import s10k.node.sim.filter.domain.SettingsExport;
import s10k.node.sim.filter.domain.SettingsExportRequest;
import s10k.node.sim.filter.domain.SettingsImport;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.form.SettingsFormService;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.sim.SimulationException;

/**
 * Service for importing and exporting SolarNode settings CSV files.
 *
 * @author matt
 * @version 1.0
 * @see SettingsCsvImporter
 * @see SettingsCsvExporter
 */
public class SettingsCsvService {

	private final FilterTypeRegistry registry;
	private final SettingsFormService forms;

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param forms
	 *        the settings form service
	 */
	public SettingsCsvService(FilterTypeRegistry registry, SettingsFormService forms) {
		super();
		this.registry = registry;
		this.forms = forms;
	}

	/**
	 * Import a settings CSV file.
	 *
	 * @param csv
	 *        the settings CSV file content
	 * @return the import result
	 * @throws SimulationException
	 *         if SolarNode cannot import the file
	 */
	public SettingsImport importCsv(String csv) {
		return new SettingsCsvImporter(registry).importCsv(csv);
	}

	/**
	 * Export a filter configuration as a settings CSV file.
	 *
	 * @param request
	 *        the request
	 * @return the export
	 * @throws SimulationException
	 *         if the configuration cannot be exported, such as when an instance
	 *         ID is not allowed
	 */
	public SettingsExport exportCsv(SettingsExportRequest request) {
		final SimConfig config = request.config();
		if ( config == null ) {
			throw new SimulationException("The config is required.");
		}
		return new SettingsCsvExporter(registry, forms).export(config, request.styleOrDefault());
	}

}
