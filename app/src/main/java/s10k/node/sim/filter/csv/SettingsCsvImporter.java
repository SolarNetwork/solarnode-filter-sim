/* ==================================================================
 * SettingsCsvImporter.java - 18 Oct 2026 1:03:29 pm
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

import static s10k.node.sim.filter.csv.SettingsCsv.CA_PID;
import static s10k.node.sim.filter.csv.SettingsCsv.CHAIN_FILTERS;
import static s10k.node.sim.filter.csv.SettingsCsv.DATUM_QUEUE_FILTER;
import static s10k.node.sim.filter.csv.SettingsCsv.DATUM_QUEUE_KEY;
import static s10k.node.sim.filter.csv.SettingsCsv.FACTORY_SUFFIX;
import static s10k.node.sim.filter.csv.SettingsCsv.INTERNAL_PREFIX;
import static s10k.node.sim.filter.csv.SettingsCsv.NUMBERED_INSTANCE;
import static s10k.node.sim.filter.csv.SettingsCsv.SETUP_TYPE;
import static s10k.node.sim.filter.csv.SettingsCsv.UID;
import static s10k.node.sim.filter.domain.SimulationMessage.error;
import static s10k.node.sim.filter.domain.SimulationMessage.info;
import static s10k.node.sim.filter.domain.SimulationMessage.warning;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import de.siegmar.fastcsv.reader.CsvParseException;
import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.CsvRecord;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.IgnoredComponent;
import s10k.node.sim.filter.domain.SettingsImport;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.settings.SettingsApplier;
import s10k.node.sim.filter.sim.SimulationException;

/**
 * Imports a SolarNode settings CSV file as a filter configuration.
 *
 * <p>
 * The importer reads the file the way SolarNode's {@code CASettingsService}
 * imports it, as of Core Settings Support 4.2.1:
 * </p>
 *
 * <ul>
 * <li>The first line is skipped as the header, every field is trimmed, and rows
 * without a key are ignored. A file with an invalid {@code flags} or
 * {@code modified} value is rejected.</li>
 * <li>An empty value removes the setting.</li>
 * <li>The {@code .FACTORY} rows give the factory UIDs, wherever they are in the
 * file, and a key belongs to the longest factory UID it starts with. A key like
 * {@code <factoryUid>.<number>} is a factory instance even without a
 * {@code .FACTORY} row.</li>
 * <li>Settings without a type, setup settings, and SolarNode's internal
 * {@code solarnode} settings are skipped, as are the settings of instance IDs
 * that {@code INSTANCE_UID_ALLOWED} rejects.</li>
 * </ul>
 *
 * <p>
 * The instances of supported and known unsupported filter types become filter
 * configurations, and other components are ignored. Unlike SolarNode, the
 * importer keeps the settings of instance IDs that are not allowed, with a
 * warning, so the IDs can be fixed before an export.
 * </p>
 *
 * <p>
 * A chain's {@code transformUids} become its {@code filterIds}. A Service Name
 * that is not in the file becomes an external filter, so the chain keeps it.
 * The Datum Queue's {@code transformServiceUid} gives the Main chain.
 * </p>
 *
 * <p>
 * Comment lines and blank rows are kept with the component whose row follows
 * them, or with the component before them at the end of the file. A comment
 * that is a commented-out row of a component, such as a setting the user
 * switched off, is kept with that component instead.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
final class SettingsCsvImporter {

	private static final Pattern CHAIN_FILTER_INDEX = Pattern
			.compile(Pattern.quote(CHAIN_FILTERS) + "\\[(\\d+)\\]");

	private static final String CHAIN_FILTERS_COUNT = CHAIN_FILTERS + SettingsApplier.COUNT_SUFFIX;

	private final FilterTypeRegistry registry;
	private final List<SimulationMessage> messages = new ArrayList<>(8);
	private final List<SimulationMessage> errors = new ArrayList<>(2);
	private final List<Line> lines = new ArrayList<>(64);
	private final Map<Component, Instance> instances = new LinkedHashMap<>(16);
	private final Map<Component, Integer> ignored = new LinkedHashMap<>(8);
	private final Set<String> missingFactoryKeys = new LinkedHashSet<>(2);
	private final List<String> datumQueueComment = new ArrayList<>(4);
	private final List<String> discardedComment = new ArrayList<>(4);
	private List<String> factoryUids = List.of();
	private @Nullable String mainUid;

	/**
	 * A component of a settings CSV file.
	 *
	 * @param key
	 *        the factory UID of a factory instance, or else the setting key
	 * @param instanceKey
	 *        the factory instance key, or {@code null} if the component is not
	 *        a factory instance
	 */
	private record Component(String key, @Nullable String instanceKey) {

	}

	/** A line of a settings CSV file, after its header. */
	private sealed interface Line permits CommentLine, BlankLine, SettingRow {

	}

	/**
	 * A comment line.
	 *
	 * @param number
	 *        the line number
	 * @param text
	 *        the line, starting with {@code #}
	 */
	private record CommentLine(long number, String text) implements Line {

	}

	/**
	 * A row with only empty fields.
	 *
	 * @param number
	 *        the line number
	 * @param text
	 *        the row, like {@code ,,,,,}
	 */
	private record BlankLine(long number, String text) implements Line {

	}

	/**
	 * A setting row.
	 *
	 * @param number
	 *        the line number
	 * @param key
	 *        the key
	 * @param type
	 *        the type, which is the setting key of a component setting
	 * @param value
	 *        the value, or {@code null} to remove the setting
	 * @param flags
	 *        the flags mask
	 * @param note
	 *        the note, or {@code null}
	 */
	private record SettingRow(long number, String key, String type, @Nullable String value, int flags,
			@Nullable String note) implements Line {

	}

	/** A filter instance. */
	private static final class Instance {

		private final Component component;
		private final Map<String, String> settings = new LinkedHashMap<>(16);
		private final Map<String, String> notes = new LinkedHashMap<>(2);
		private final List<String> comment = new ArrayList<>(4);
		private boolean factoryRow;
		private boolean skipped;
		private boolean flags;

		private Instance(Component component) {
			super();
			this.component = component;
		}

		private String factoryUid() {
			return component.key();
		}

		private String instanceKey() {
			final String key = component.instanceKey();
			return (key != null ? key : "");
		}

		private String id() {
			return factoryUid() + '.' + instanceKey();
		}

		private String label() {
			final String uid = settings.get(UID);
			return (uid != null && !uid.isBlank() ? uid : id());
		}

	}

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 */
	SettingsCsvImporter(FilterTypeRegistry registry) {
		super();
		this.registry = registry;
	}

	/**
	 * Import a settings CSV file.
	 *
	 * <p>
	 * An importer can import only one file.
	 * </p>
	 *
	 * @param csv
	 *        the settings CSV file content
	 * @return the import result
	 * @throws SimulationException
	 *         if SolarNode cannot import the file
	 */
	SettingsImport importCsv(String csv) {
		final String text = (csv.startsWith("﻿") ? csv.substring(1) : csv);
		checkHeader(text);
		read(text);
		if ( !errors.isEmpty() ) {
			throw new SimulationException("SolarNode cannot import this settings CSV file.", errors);
		}

		// like SolarNode, find the factory UIDs first, longest first, because one factory UID
		// can be the start of another
		final Set<String> uids = new LinkedHashSet<>(8);
		for ( Line line : lines ) {
			if ( line instanceof SettingRow row && row.key().endsWith(FACTORY_SUFFIX) ) {
				uids.add(row.key().substring(0, row.key().length() - FACTORY_SUFFIX.length()));
			}
		}
		factoryUids = uids.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();

		final List<@Nullable List<String>> targets = new ArrayList<>(lines.size());
		for ( Line line : lines ) {
			targets.add(line instanceof SettingRow row ? apply(row) : null);
		}
		attachComments(targets);
		return result();
	}

	private void checkHeader(String text) {
		final int end = text.indexOf('\n');
		final String first = (end < 0 ? text : text.substring(0, end));
		final String key = first.split(",", 2)[0].replace("\"", "").strip();
		if ( !"key".equalsIgnoreCase(key) ) {
			messages.add(warning(SimulationMessage.CODE_CSV_HEADER,
					"The first line is not the key,type,value,flags,modified,note header. SolarNode skips the first line of a settings CSV file, so the simulator did too.",
					null, null));
		}
	}

	private void read(String text) {
		try (CsvReader<CsvRecord> reader = SettingsCsv.reader(text)) {
			reader.skipLines(1);
			for ( CsvRecord r : reader ) {
				final long number = r.getStartingLineNumber();
				if ( r.isComment() ) {
					lines.add(new CommentLine(number, "#" + r.getField(0)));
				} else if ( !r.getField(0).isEmpty() ) {
					final SettingRow row = row(r, number);
					if ( row != null ) {
						lines.add(row);
					}
				} else if ( r.getFields().stream().allMatch(String::isEmpty) ) {
					lines.add(new BlankLine(number, ",".repeat(r.getFieldCount() - 1)));
				} else {
					messages.add(warning(SimulationMessage.CODE_SETTING_SKIPPED,
							"Line %d has no key, so SolarNode skips it.".formatted(number), null, null));
				}
			}
		} catch ( CsvParseException e ) {
			errors.add(error(SimulationMessage.CODE_CSV_FORMAT,
					"The file is not valid CSV: %s".formatted(e.getMessage()), null));
		} catch ( IOException e ) {
			throw new UncheckedIOException(e);
		}
	}

	/**
	 * Parse a setting row, like SolarNode's {@code CASettingsService}.
	 *
	 * @param r
	 *        the record, with a key
	 * @param number
	 *        the line number
	 * @return the row, or {@code null} if it has an invalid value
	 */
	private @Nullable SettingRow row(CsvRecord r, long number) {
		final String flags = field(r, 3);
		int mask = 0;
		boolean valid = true;
		if ( flags != null ) {
			try {
				mask = Integer.parseInt(flags);
			} catch ( NumberFormatException e ) {
				errors.add(error(SimulationMessage.CODE_CSV_FORMAT,
						"Line %d has the flags %s, which is not a number.".formatted(number, flags),
						null));
				valid = false;
			}
		}
		final String modified = field(r, 4);
		if ( modified != null && !isModifiedDate(modified) ) {
			errors.add(error(SimulationMessage.CODE_CSV_FORMAT,
					"Line %d has the modified date %s, which is not like 2025-10-01 12:34:56."
							.formatted(number, modified),
					null));
			valid = false;
		}
		if ( !valid ) {
			return null;
		}
		final String type = field(r, 1);
		return new SettingRow(number, r.getField(0), type != null ? type : "", field(r, 2), mask,
				field(r, 5));
	}

	private static @Nullable String field(CsvRecord r, int index) {
		if ( index >= r.getFieldCount() ) {
			return null;
		}
		final String value = r.getField(index);
		return (value.isEmpty() ? null : value);
	}

	private static boolean isModifiedDate(String value) {
		try {
			return SettingsCsv.MODIFIED_FORMAT.parse(value, Instant::from) != null;
		} catch ( DateTimeParseException e ) {
			return false;
		}
	}

	/**
	 * Apply a setting row, like SolarNode's
	 * {@code CASettingsService.applyImportedSettings()}.
	 *
	 * @param row
	 *        the row
	 * @return the comment lines of the row's component
	 */
	private List<String> apply(SettingRow row) {
		final String key = row.key();
		if ( key.regionMatches(true, 0, INTERNAL_PREFIX, 0, INTERNAL_PREFIX.length()) ) {
			return ignore(new Component(key, null));
		}
		if ( key.endsWith(FACTORY_SUFFIX) ) {
			final Component c = new Component(key.substring(0, key.length() - FACTORY_SUFFIX.length()),
					row.type());
			if ( row.type().isEmpty() || !isFilterType(c.key()) ) {
				return ignore(c);
			}
			if ( row.value() == null ) {
				// the row removes the instance
				final Instance instance = instances.get(c);
				return (instance != null ? instance.comment : ignore(c));
			}
			final Instance instance = instances.computeIfAbsent(c, Instance::new);
			instance.factoryRow = true;
			return instance.comment;
		}
		final Component c = component(key);
		final String instanceKey = c.instanceKey();
		if ( instanceKey == null || !isFilterType(c.key()) ) {
			if ( DATUM_QUEUE_KEY.equals(key) ) {
				if ( DATUM_QUEUE_FILTER.equals(row.type()) ) {
					mainUid = row.value();
				} else {
					ignore(c);
				}
				return datumQueueComment;
			}
			if ( instanceKey == null ) {
				checkMissingFactory(key);
			}
			return ignore(c);
		}
		final Instance instance = instances.computeIfAbsent(c, Instance::new);
		if ( row.type().isEmpty() ) {
			messages.add(warning(SimulationMessage.CODE_SETTING_SKIPPED,
					"Line %d has no setting key in its type column, so SolarNode skips it."
							.formatted(row.number()),
					instance.id(), null));
			return instance.comment;
		}
		if ( SETUP_TYPE.equals(row.type()) ) {
			return instance.comment;
		}
		if ( !(CA_PID.matcher(key).matches() || (CA_PID.matcher(c.key()).matches()
				&& InstanceIds.SERVER_PATTERN.matcher(instanceKey).matches())) ) {
			instance.skipped = true;
		}
		if ( row.value() == null ) {
			instance.settings.remove(row.type());
			instance.notes.remove(row.type());
		} else {
			instance.settings.put(row.type(), row.value());
			if ( row.note() != null ) {
				instance.notes.put(row.type(), row.note());
			} else {
				instance.notes.remove(row.type());
			}
		}
		if ( row.flags() != 0 ) {
			instance.flags = true;
		}
		return instance.comment;
	}

	/**
	 * Find the component of a key, like SolarNode.
	 *
	 * @param key
	 *        the key
	 * @return the component
	 */
	private Component component(String key) {
		for ( String factoryUid : factoryUids ) {
			if ( key.length() > factoryUid.length() + 1 && key.startsWith(factoryUid)
					&& key.charAt(factoryUid.length()) == '.' ) {
				return new Component(factoryUid, key.substring(factoryUid.length() + 1));
			}
		}
		final Matcher m = NUMBERED_INSTANCE.matcher(key);
		if ( m.matches() ) {
			return new Component(m.group(1), m.group(2));
		}
		return new Component(key, null);
	}

	private boolean isFilterType(String factoryUid) {
		return (registry.definition(factoryUid) != null
				|| StandardFilterTypes.UNSUPPORTED.containsKey(factoryUid));
	}

	private List<String> ignore(Component c) {
		ignored.merge(c, 1, Integer::sum);
		return discardedComment;
	}

	/**
	 * Warn about the settings of a filter instance that SolarNode does not
	 * import, because the file has no {@code .FACTORY} row for it.
	 *
	 * @param key
	 *        the key of a component that is not a factory instance
	 */
	private void checkMissingFactory(String key) {
		if ( missingFactoryKeys.contains(key) ) {
			return;
		}
		String factoryUid = null;
		for ( String uid : filterTypeUids() ) {
			if ( key.startsWith(uid + ".")
					&& (factoryUid == null || uid.length() > factoryUid.length()) ) {
				factoryUid = uid;
			}
		}
		if ( factoryUid != null ) {
			missingFactoryKeys.add(key);
			messages.add(warning(SimulationMessage.CODE_MISSING_FACTORY,
					"The file has no %s%s row, so SolarNode does not import the %s settings."
							.formatted(factoryUid, FACTORY_SUFFIX, key),
					null, null));
		}
	}

	private List<String> filterTypeUids() {
		final List<String> result = new ArrayList<>(
				registry.definitions().size() + StandardFilterTypes.UNSUPPORTED.size());
		registry.definitions().forEach(d -> result.add(d.factoryUid()));
		result.addAll(StandardFilterTypes.UNSUPPORTED.keySet());
		return result;
	}

	private void attachComments(List<@Nullable List<String>> targets) {
		final List<String> pending = new ArrayList<>(8);
		List<String> last = null;
		for ( int i = 0, len = lines.size(); i < len; i++ ) {
			switch (lines.get(i)) {
				case CommentLine c -> {
					final List<String> owner = commentOwner(c.text());
					if ( owner != null ) {
						owner.add(c.text());
					} else {
						pending.add(c.text());
					}
				}
				case BlankLine b -> pending.add(b.text());
				case SettingRow _ -> {
					last = targets.get(i);
					if ( last != null ) {
						last.addAll(pending);
					}
					pending.clear();
				}
			}
		}
		if ( last != null ) {
			last.addAll(pending);
		}
	}

	/**
	 * Find the component that a commented-out row belongs to.
	 *
	 * @param text
	 *        the comment line
	 * @return the comment lines of the component, or {@code null} if the
	 *         comment is not a row of a component in the file
	 */
	private @Nullable List<String> commentOwner(String text) {
		final CsvRecord r;
		try {
			r = CsvReader.builder().ofSingleCsvRecord(text.substring(1));
		} catch ( CsvParseException e ) {
			return null;
		}
		final String key = r.getField(0).strip();
		if ( key.isEmpty() ) {
			return null;
		}
		final Component c;
		if ( key.endsWith(FACTORY_SUFFIX) ) {
			c = new Component(key.substring(0, key.length() - FACTORY_SUFFIX.length()),
					r.getFieldCount() > 1 ? r.getField(1).strip() : "");
		} else if ( DATUM_QUEUE_KEY.equals(key) ) {
			return datumQueueComment;
		} else {
			c = component(key);
		}
		final Instance instance = instances.get(c);
		if ( instance != null ) {
			return instance.comment;
		}
		return (ignored.containsKey(c) ? discardedComment : null);
	}

	private SettingsImport result() {
		final Map<String, String> idsByUid = new LinkedHashMap<>(instances.size());
		final Map<String, List<String>> duplicateUids = new LinkedHashMap<>(2);
		final Map<String, List<String>> chainUids = new LinkedHashMap<>(4);
		for ( Instance instance : instances.values() ) {
			instanceMessages(instance);
			if ( StandardFilterTypes.CHAIN.equals(instance.factoryUid()) ) {
				final List<String> members = chainMembers(instance);
				if ( members != null ) {
					chainUids.put(instance.id(), members);
				}
			}
			final String uid = instance.settings.get(UID);
			if ( uid != null && !uid.isBlank() ) {
				final String existing = idsByUid.putIfAbsent(uid, instance.id());
				if ( existing != null ) {
					duplicateUids.computeIfAbsent(uid, _ -> new ArrayList<>(List.of(existing)))
							.add(instance.id());
				}
			}
		}
		for ( Map.Entry<String, List<String>> e : duplicateUids.entrySet() ) {
			messages.add(warning(SimulationMessage.CODE_DUPLICATE_UID,
					"The Service Name %s is used by %d filters (%s), so filter chains use only the first one."
							.formatted(e.getKey(), e.getValue().size(), String.join(", ", e.getValue())),
					e.getValue().getFirst(), null));
		}

		final List<FilterConfig> filters = new ArrayList<>(instances.size() + 2);
		final Map<String, FilterConfig> externals = new LinkedHashMap<>(2);
		for ( Instance instance : instances.values() ) {
			final List<String> uids = chainUids.get(instance.id());
			List<String> filterIds = null;
			if ( uids != null ) {
				filterIds = new ArrayList<>(uids.size());
				for ( String uid : uids ) {
					String memberId = idsByUid.get(uid);
					if ( memberId == null ) {
						FilterConfig external = externals.get(uid);
						if ( external == null ) {
							external = new FilterConfig(FilterConfig.EXTERNAL_FACTORY_UID + ':' + uid,
									FilterConfig.EXTERNAL_FACTORY_UID, null, Map.of(UID, uid), null);
							externals.put(uid, external);
							messages.add(warning(SimulationMessage.CODE_EXTERNAL_FILTER,
									"The chain %s uses the filter %s, which is not in the file. It is kept as an external filter, which datum pass through unchanged."
											.formatted(instance.label(), uid),
									external.id(), null));
						}
						memberId = external.id();
					}
					filterIds.add(memberId);
				}
			}
			filters.add(new FilterConfig(instance.id(), instance.factoryUid(), instance.instanceKey(),
					instance.settings, filterIds, comment(instance.comment),
					instance.notes.isEmpty() ? null : instance.notes));
		}
		filters.addAll(externals.values());

		String mainChainId = null;
		if ( mainUid != null ) {
			mainChainId = idsByUid.get(mainUid);
			if ( mainChainId == null ) {
				messages.add(warning(SimulationMessage.CODE_MAIN_CHAIN,
						"The Datum Queue uses the filter %s, which is not in the file."
								.formatted(mainUid),
						null, null));
			}
		}

		final List<IgnoredComponent> ignoredComponents = new ArrayList<>(ignored.size());
		for ( Map.Entry<Component, Integer> e : ignored.entrySet() ) {
			final Component c = e.getKey();
			ignoredComponents.add(new IgnoredComponent(c.key(), c.instanceKey(), e.getValue()));
			if ( c.instanceKey() != null && StandardFilterTypes.GLOBAL.contains(c.key()) ) {
				messages.add(info(SimulationMessage.CODE_GLOBAL_FILTER,
						"The global datum filter %s.%s is ignored, because the simulator does not support global filters."
								.formatted(c.key(), c.instanceKey()),
						null));
			}
		}
		return new SettingsImport(new SimConfig(filters, mainChainId, comment(datumQueueComment)),
				messages, ignoredComponents);
	}

	private void instanceMessages(Instance instance) {
		final String name = StandardFilterTypes.UNSUPPORTED.get(instance.factoryUid());
		if ( name != null ) {
			messages.add(info(SimulationMessage.CODE_UNSUPPORTED_FILTER,
					"The %s %s is not supported, so datum pass through it unchanged. Its settings are kept as they are."
							.formatted(name, instance.label()),
					instance.id()));
		}
		final String problem = InstanceIds.problem(instance.instanceKey());
		if ( instance.skipped ) {
			messages.add(warning(SimulationMessage.CODE_INVALID_INSTANCE_ID,
					"SolarNode skips the settings of the filter %s, because its instance ID %s is not allowed. Change the instance ID before you export the settings."
							.formatted(instance.label(), instance.instanceKey()),
					instance.id(), null));
		} else if ( problem != null ) {
			messages.add(warning(SimulationMessage.CODE_INVALID_INSTANCE_ID,
					"The instance ID %s of the filter %s is not allowed: %s Change it before you export the settings."
							.formatted(instance.instanceKey(), instance.label(), problem),
					instance.id(), null));
		}
		if ( !instance.factoryRow ) {
			messages.add(info(SimulationMessage.CODE_MISSING_FACTORY,
					"The file has no %s%s row for the filter %s, which SolarNode uses to list the component. An export adds the row."
							.formatted(instance.factoryUid(), FACTORY_SUFFIX, instance.label()),
					instance.id()));
		}
		if ( instance.flags ) {
			messages.add(warning(SimulationMessage.CODE_SETTING_FLAGS,
					"Some settings of the filter %s have flags, which the simulator does not keep."
							.formatted(instance.label()),
					instance.id(), null));
		}
	}

	/**
	 * Remove a chain's {@code transformUids} settings, and get its members.
	 *
	 * <p>
	 * Like the chain's {@code transformUidsCount} setting, only that many
	 * members are used.
	 * </p>
	 *
	 * @param chain
	 *        the chain
	 * @return the member Service Names, in order, or {@code null} if the chain
	 *         has no {@code transformUids} settings
	 */
	private @Nullable List<String> chainMembers(Instance chain) {
		boolean found = false;
		Integer count = null;
		final SortedMap<Integer, String> uids = new TreeMap<>();
		for ( Iterator<Map.Entry<String, String>> itr = chain.settings.entrySet().iterator(); itr
				.hasNext(); ) {
			final Map.Entry<String, String> e = itr.next();
			final String key = e.getKey();
			if ( !key.startsWith(CHAIN_FILTERS) ) {
				continue;
			}
			found = true;
			itr.remove();
			chain.notes.remove(key);
			if ( CHAIN_FILTERS_COUNT.equals(key) ) {
				try {
					count = Integer.valueOf(e.getValue());
				} catch ( NumberFormatException ex ) {
					messages.add(
							warning(SimulationMessage.CODE_INVALID_SETTING,
									"The chain %s has the %s %s, which is not a number."
											.formatted(chain.label(), CHAIN_FILTERS_COUNT, e.getValue()),
									chain.id(), null));
				}
			} else {
				final Matcher m = CHAIN_FILTER_INDEX.matcher(key);
				if ( m.matches() ) {
					uids.put(Integer.valueOf(m.group(1)), e.getValue());
				}
			}
		}
		if ( !found ) {
			return null;
		}
		final List<String> result = new ArrayList<>(uids.size());
		for ( Map.Entry<Integer, String> e : uids.entrySet() ) {
			if ( count != null && e.getKey() >= count ) {
				messages.add(warning(SimulationMessage.CODE_INVALID_SETTING,
						"The chain %s has a %s[%d] setting, but its %s is %d, so it is ignored."
								.formatted(chain.label(), CHAIN_FILTERS, e.getKey(), CHAIN_FILTERS_COUNT,
										count),
						chain.id(), null));
			} else if ( !e.getValue().isBlank() ) {
				result.add(e.getValue());
			}
		}
		return result;
	}

	/**
	 * Join comment lines, without leading or trailing blank rows.
	 *
	 * @param lines
	 *        the comment lines and blank rows
	 * @return the comment, or {@code null} if there are no comment lines
	 */
	private static @Nullable String comment(List<String> lines) {
		int start = 0;
		int end = lines.size();
		while ( start < end && !lines.get(start).startsWith("#") ) {
			start++;
		}
		while ( end > start && !lines.get(end - 1).startsWith("#") ) {
			end--;
		}
		return (start < end ? String.join("\n", lines.subList(start, end)) : null);
	}

}
