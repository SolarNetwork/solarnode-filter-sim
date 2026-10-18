/* ==================================================================
 * SettingsCsvController.java - 18 Oct 2026 1:03:29 pm
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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import s10k.node.sim.filter.csv.SettingsCsvService;
import s10k.node.sim.filter.domain.SettingsExport;
import s10k.node.sim.filter.domain.SettingsExportRequest;
import s10k.node.sim.filter.domain.SettingsImport;

/**
 * Web API for importing and exporting SolarNode settings CSV files.
 *
 * @author matt
 * @version 1.0
 */
@RestController
@RequestMapping("/api/settings")
public class SettingsCsvController {

	/** The settings CSV media type. */
	public static final String TEXT_CSV_VALUE = "text/csv";

	private final SettingsCsvService service;

	/**
	 * Constructor.
	 *
	 * @param service
	 *        the settings CSV service
	 */
	public SettingsCsvController(SettingsCsvService service) {
		super();
		this.service = service;
	}

	/**
	 * Import a settings CSV file, sent as the request body.
	 *
	 * @param csv
	 *        the file content, in UTF-8
	 * @return the import result
	 */
	@PostMapping(path = "/import", consumes = { TEXT_CSV_VALUE, MediaType.TEXT_PLAIN_VALUE })
	public SettingsImport importCsv(@RequestBody byte[] csv) {
		return service.importCsv(new String(csv, StandardCharsets.UTF_8));
	}

	/**
	 * Import a settings CSV file, uploaded as a {@code file} form part.
	 *
	 * @param file
	 *        the file, in UTF-8
	 * @return the import result
	 * @throws IOException
	 *         if the file cannot be read
	 */
	@PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public SettingsImport importCsv(@RequestPart("file") MultipartFile file) throws IOException {
		return service.importCsv(new String(file.getBytes(), StandardCharsets.UTF_8));
	}

	/**
	 * Export a filter configuration as a settings CSV file.
	 *
	 * @param request
	 *        the request
	 * @return the export
	 */
	@PostMapping("/export")
	public SettingsExport exportCsv(@RequestBody SettingsExportRequest request) {
		return service.exportCsv(request);
	}

}
