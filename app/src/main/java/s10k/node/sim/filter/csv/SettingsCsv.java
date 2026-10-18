/* ==================================================================
 * SettingsCsv.java - 18 Oct 2026 1:03:29 pm
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

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Pattern;
import de.siegmar.fastcsv.reader.CommentStrategy;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import de.siegmar.fastcsv.reader.CsvRecordHandler;
import de.siegmar.fastcsv.reader.FieldMismatchStrategy;
import de.siegmar.fastcsv.reader.FieldModifiers;

/**
 * The SolarNode settings CSV format.
 *
 * <p>
 * SolarNode reads and writes this format in its {@code CASettingsService}. Each
 * row is a setting: {@code key,type,value,flags,modified,note}. A factory
 * instance's settings have the key {@code <factoryUid>.<instanceKey>} and the
 * setting key as the type, and a {@code <factoryUid>.FACTORY} row with the
 * instance key as both type and value registers the instance.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class SettingsCsv {

	/** The CSV column names. */
	public static final List<String> HEADER = List.of("key", "type", "value", "flags", "modified",
			"note");

	/** The key suffix of the rows that register factory instances. */
	public static final String FACTORY_SUFFIX = ".FACTORY";

	/** The Datum Queue component key. */
	public static final String DATUM_QUEUE_KEY = "net.solarnetwork.node.runtime.dq";

	/** The Datum Queue setting that holds the Service Name of its filter. */
	public static final String DATUM_QUEUE_FILTER = "transformServiceUid";

	/** The Service Name setting key. */
	public static final String UID = "uid";

	/** The setting key of a filter chain's list of filters. */
	public static final String CHAIN_FILTERS = "transformUids";

	/**
	 * The instance key SolarNode uses for the Main chain, by convention.
	 */
	public static final String MAIN_INSTANCE_KEY = "Main";

	/**
	 * The keys SolarNode restores from a settings CSV file, besides factory
	 * instances: {@code CASettingsService.CA_PID_PATTERN}.
	 */
	static final Pattern CA_PID = Pattern.compile("^[a-zA-Z0-9.]+$");

	/**
	 * A key that looks like a factory instance with a number instance key,
	 * which SolarNode treats as one even without a {@code .FACTORY} row:
	 * {@code CASettingsService.SETTING_FACTORY_INSTANCE_PATTERN}.
	 */
	static final Pattern NUMBERED_INSTANCE = Pattern.compile("^([a-zA-Z0-9.]+)\\.(\\d+)$");

	/**
	 * The key prefix of SolarNode's internal settings, which it does not
	 * import, compared without case.
	 */
	static final String INTERNAL_PREFIX = "solarnode";

	/** The type of SolarNode setup settings, which it does not import. */
	static final String SETUP_TYPE = "solarnode.setup";

	/** The format of the {@code modified} column, in UTC. */
	static final DateTimeFormatter MODIFIED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneOffset.UTC);

	private SettingsCsv() {
		// not available
	}

	/**
	 * Create a settings CSV reader.
	 *
	 * <p>
	 * The reader uses SolarNode's options: it allows missing and extra fields,
	 * skips empty lines, and trims every field. It also returns comment lines,
	 * which SolarNode skips, so they can be kept.
	 * </p>
	 *
	 * @param csv
	 *        the CSV text
	 * @return the reader
	 */
	static CsvReader<CsvRecord> reader(String csv) {
		return CsvReader.builder().missingFieldStrategy(FieldMismatchStrategy.IGNORE)
				.extraFieldStrategy(FieldMismatchStrategy.IGNORE).skipEmptyLines(true)
				.commentStrategy(CommentStrategy.READ)
				.build(CsvRecordHandler.builder().fieldModifier(FieldModifiers.TRIM).build(), csv);
	}

}
