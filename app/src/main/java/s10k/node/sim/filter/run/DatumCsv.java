/* ==================================================================
 * DatumCsv.java - 30 Oct 2026 10:12:41 pm
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

package s10k.node.sim.filter.run;

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;
import de.siegmar.fastcsv.writer.CsvWriter;

/**
 * Datum as CSV, in the style of SolarNetwork's datum stream CSV.
 *
 * <p>
 * The columns are {@code ts} (an ISO instant), {@code nodeId},
 * {@code sourceId}, a column for each property, and {@code tags}, joined into
 * one comma-delimited value. The property columns are the instantaneous
 * properties, then the accumulating, then the status, each in the order the
 * datum first have them. A property name has one column, whatever its type, so
 * a name already used by another type adds no column.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class DatumCsv {

	/** The name of the timestamp column. */
	public static final String TIMESTAMP_COLUMN = "ts";

	/** The name of the node ID column. */
	public static final String NODE_ID_COLUMN = "nodeId";

	/** The name of the source ID column. */
	public static final String SOURCE_ID_COLUMN = "sourceId";

	/** The name of the tags column. */
	public static final String TAGS_COLUMN = "tags";

	/** The number of columns before the properties. */
	private static final int META_COLUMNS = 3;

	private DatumCsv() {
		// not available
	}

	/**
	 * The property names of some datum, all together and per source ID, to work
	 * out the columns before writing them.
	 */
	public static final class Columns {

		private final Names all = new Names();
		private final Map<String, Names> sources = new LinkedHashMap<>(8);

		/**
		 * Add the property names of a datum.
		 *
		 * @param datum
		 *        the datum
		 */
		public synchronized void add(DownloadDatum datum) {
			all.add(datum);
			sources.computeIfAbsent(sourceId(datum), _ -> new Names()).add(datum);
		}

		/**
		 * Get the property columns of every datum.
		 *
		 * @return the property names, in column order
		 */
		public synchronized String[] all() {
			return all.columns();
		}

		/**
		 * Get the source IDs.
		 *
		 * @return the source IDs, in the order the datum first have them
		 */
		public synchronized Set<String> sourceIds() {
			return new LinkedHashSet<>(sources.keySet());
		}

		/**
		 * Get the property columns of a source ID's datum.
		 *
		 * @param sourceId
		 *        the source ID
		 * @return the property names, in column order
		 */
		public synchronized String[] source(String sourceId) {
			final Names names = sources.get(sourceId);
			return (names != null ? names.columns() : new String[0]);
		}

	}

	/** The property names of each type, in the order the datum have them. */
	private static final class Names {

		private final Set<String> i = new LinkedHashSet<>(8);
		private final Set<String> a = new LinkedHashSet<>(8);
		private final Set<String> s = new LinkedHashSet<>(8);

		private void add(DownloadDatum datum) {
			addAll(i, datum.i());
			addAll(a, datum.a());
			addAll(s, datum.s());
		}

		private static void addAll(Set<String> names, @Nullable Map<String, Object> props) {
			if ( props != null ) {
				names.addAll(props.keySet());
			}
		}

		private String[] columns() {
			final Set<String> result = new LinkedHashSet<>(i);
			result.addAll(a);
			result.addAll(s);
			return result.toArray(String[]::new);
		}

	}

	/**
	 * Write datum as one CSV, with the columns of them all.
	 *
	 * @param datum
	 *        the datum
	 * @param columns
	 *        the property names of the datum
	 * @param nodeId
	 *        the node ID
	 * @param out
	 *        the stream to write to, which is flushed but not closed
	 * @throws IOException
	 *         if writing fails
	 */
	public static void write(Iterator<DownloadDatum> datum, Columns columns, long nodeId,
			OutputStream out) throws IOException {
		final Sheet sheet = new Sheet(new OutputStreamWriter(out, UTF_8), columns.all());
		while ( datum.hasNext() ) {
			sheet.write(datum.next(), nodeId);
		}
		sheet.writer.flush();
	}

	/**
	 * Write datum as a zip archive of one CSV per source ID, each with the
	 * columns of its own datum.
	 *
	 * @param datum
	 *        the datum
	 * @param columns
	 *        the property names of the datum
	 * @param nodeId
	 *        the node ID
	 * @param out
	 *        the stream to write to, which is finished but not closed
	 * @throws IOException
	 *         if writing fails
	 */
	public static void writeZip(Iterator<DownloadDatum> datum, Columns columns, long nodeId,
			OutputStream out) throws IOException {
		// each source's CSV is kept compressed until the datum are all read
		final Map<String, Buffer> buffers = new LinkedHashMap<>(8);
		final Set<String> names = new HashSet<>(8);
		for ( String sourceId : columns.sourceIds() ) {
			buffers.put(sourceId, new Buffer(uniqueFileName(sourceId, names), columns.source(sourceId)));
		}
		while ( datum.hasNext() ) {
			final DownloadDatum d = datum.next();
			final Buffer buffer = buffers.get(sourceId(d));
			if ( buffer != null ) {
				buffer.sheet.write(d, nodeId);
			}
		}
		final ZipOutputStream zip = new ZipOutputStream(out, UTF_8);
		for ( Buffer buffer : buffers.values() ) {
			buffer.sheet.writer.close();
			zip.putNextEntry(new ZipEntry(buffer.fileName));
			try (InputStream in = new InflaterInputStream(
					new ByteArrayInputStream(buffer.bytes.toByteArray()))) {
				in.transferTo(zip);
			}
			zip.closeEntry();
		}
		zip.finish();
		out.flush();
	}

	/**
	 * Get a file name for a source ID's CSV.
	 *
	 * <p>
	 * Each character other than a letter, digit, {@code .}, {@code _}, or
	 * {@code -} is {@code _}, without leading or trailing {@code _} or
	 * {@code .}, so {@code /meter/1} is {@code meter_1.csv}.
	 * </p>
	 *
	 * @param sourceId
	 *        the source ID
	 * @return the file name
	 */
	public static String fileName(String sourceId) {
		final String name = sourceId.replaceAll("[^\\p{L}\\p{N}._-]", "_").replaceAll("^[_.]+|[_.]+$",
				"");
		return (name.isEmpty() ? "datum" : name) + ".csv";
	}

	private static String uniqueFileName(String sourceId, Set<String> used) {
		final String name = fileName(sourceId);
		final String base = name.substring(0, name.length() - 4);
		String result = name;
		for ( int n = 2; !used.add(result.toLowerCase(Locale.ROOT)); n++ ) {
			result = "%s-%d.csv".formatted(base, n);
		}
		return result;
	}

	private static String sourceId(DownloadDatum datum) {
		final String sourceId = datum.sourceId();
		return (sourceId != null ? sourceId : "");
	}

	/** A source's CSV, compressed. */
	private static final class Buffer {

		private final String fileName;
		private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(4 * 1024);
		private final Sheet sheet;

		private Buffer(String fileName, String[] columns) throws IOException {
			super();
			this.fileName = fileName;
			this.sheet = new Sheet(new OutputStreamWriter(new DeflaterOutputStream(bytes), UTF_8),
					columns);
		}

	}

	/** A CSV being written, with its columns. */
	private static final class Sheet {

		private final CsvWriter writer;
		private final Map<String, Integer> columns;
		private final int count;

		private Sheet(Writer out, String[] names) throws IOException {
			super();
			this.writer = CsvWriter.builder().build(out);
			this.columns = new HashMap<>(names.length);
			for ( int i = 0; i < names.length; i++ ) {
				columns.put(names[i], META_COLUMNS + i);
			}
			this.count = META_COLUMNS + names.length + 1;
			final String[] header = new String[count];
			header[0] = TIMESTAMP_COLUMN;
			header[1] = NODE_ID_COLUMN;
			header[2] = SOURCE_ID_COLUMN;
			System.arraycopy(names, 0, header, META_COLUMNS, names.length);
			header[count - 1] = TAGS_COLUMN;
			writer.writeRecord(header);
		}

		private void write(DownloadDatum datum, long nodeId) {
			final String[] row = new String[count];
			Arrays.fill(row, "");
			row[0] = timestamp(datum.created());
			row[1] = Long.toString(nodeId);
			row[2] = sourceId(datum);
			// like SolarNetwork, a later type's value wins for a name
			put(row, datum.i());
			put(row, datum.a());
			put(row, datum.s());
			final Set<String> tags = datum.t();
			if ( tags != null ) {
				row[count - 1] = String.join(",", tags);
			}
			writer.writeRecord(row);
		}

		private void put(String[] row, @Nullable Map<String, Object> props) {
			if ( props == null ) {
				return;
			}
			for ( Map.Entry<String, Object> e : props.entrySet() ) {
				final Integer idx = columns.get(e.getKey());
				if ( idx != null ) {
					row[idx] = value(e.getValue());
				}
			}
		}

	}

	private static String timestamp(@Nullable String created) {
		if ( created == null ) {
			return "";
		}
		try {
			return DateTimeFormatter.ISO_INSTANT
					.format(DownloadDatum.CREATED_FORMAT.parse(created, Instant::from));
		} catch ( DateTimeParseException e ) {
			return created;
		}
	}

	private static String value(@Nullable Object value) {
		return switch (value) {
			case null -> "";
			case BigDecimal d -> d.toPlainString();
			default -> value.toString();
		};
	}

}
