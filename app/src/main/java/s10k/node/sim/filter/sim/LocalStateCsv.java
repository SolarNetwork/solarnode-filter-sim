/* ==================================================================
 * LocalStateCsv.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.sim;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import de.siegmar.fastcsv.reader.CommentStrategy;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import de.siegmar.fastcsv.reader.CsvRecordHandler;
import de.siegmar.fastcsv.reader.FieldMismatchStrategy;
import de.siegmar.fastcsv.reader.FieldModifiers;
import de.siegmar.fastcsv.writer.CsvWriter;
import net.solarnetwork.node.domain.LocalStateType;

/**
 * Support for the SolarNode Local State CSV format.
 *
 * <p>
 * The format has the columns {@code Key,Created,Modified,Type,Value}. The
 * created and modified dates are ISO-8601 instants, and the type is a
 * {@link LocalStateType} name or key. This class reads the CSV with the same
 * options as SolarNode's {@code CsvConfigurableBackupServiceSupport}, checks
 * each row so problems can be reported by line, and fills in blank dates, which
 * SolarNode does not accept.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class LocalStateCsv {

	/** The CSV column names. */
	private static final String[] HEADER = new String[] { "Key", "Created", "Modified", "Type",
			"Value" };

	private LocalStateCsv() {
		// not available
	}

	/**
	 * Check Local State CSV and fill in blank dates.
	 *
	 * @param csv
	 *        the CSV, with a header row
	 * @param defaultDate
	 *        the date to use for blank created and modified dates
	 * @return the CSV, ready for SolarNode to import
	 * @throws SimulationException
	 *         if a row is not valid
	 */
	public static String normalize(String csv, Instant defaultDate) {
		final StringWriter out = new StringWriter(csv.length() + 64);
		try (CsvReader<CsvRecord> reader = CsvReader.builder()
				.missingFieldStrategy(FieldMismatchStrategy.IGNORE)
				.extraFieldStrategy(FieldMismatchStrategy.IGNORE).commentStrategy(CommentStrategy.SKIP)
				.build(CsvRecordHandler.builder().fieldModifier(FieldModifiers.TRIM).build(),
						new StringReader(csv));
				CsvWriter writer = CsvWriter.builder().build(out)) {
			reader.skipLines(1);
			writer.writeRecord(HEADER);
			for ( CsvRecord row : reader ) {
				if ( row.getFieldCount() < HEADER.length ) {
					// SolarNode skips these rows
					continue;
				}
				final long line = row.getStartingLineNumber();
				final String key = row.getField(0);
				if ( key.isEmpty() ) {
					throw new SimulationException("Local State CSV line %d has no key.".formatted(line));
				}
				final String created = date(row.getField(1), defaultDate, line, "Created");
				final String modified = date(row.getField(2), defaultDate, line, "Modified");
				final LocalStateType type;
				try {
					type = LocalStateType.forKey(row.getField(3));
				} catch ( IllegalArgumentException e ) {
					throw new SimulationException("Local State CSV line %d has an unsupported type: %s"
							.formatted(line, row.getField(3)), e);
				}
				final String value = row.getField(4);
				try {
					type.encode(value);
				} catch ( RuntimeException e ) {
					throw new SimulationException("Local State CSV line %d has an invalid %s value: %s"
							.formatted(line, type.name(), value), e);
				}
				writer.writeRecord(key, created, modified, type.name(), value);
			}
		} catch ( IOException e ) {
			throw new UncheckedIOException(e);
		}
		return out.toString();
	}

	private static String date(String value, Instant defaultDate, long line, String column) {
		if ( value.isEmpty() ) {
			return defaultDate.toString();
		}
		try {
			return Instant.parse(value).toString();
		} catch ( DateTimeParseException e ) {
			throw new SimulationException(
					"Local State CSV line %d has an invalid %s date (expected an ISO-8601 instant like 2025-10-01T00:00:00Z): %s"
							.formatted(line, column, value),
					e);
		}
	}

}
