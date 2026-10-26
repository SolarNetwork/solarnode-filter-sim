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
import java.util.HashSet;
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
import s10k.node.sim.filter.domain.ExternalFilterOrigin;
import s10k.node.sim.filter.domain.ExternalSettings;
import s10k.node.sim.filter.domain.ExternalSettingsFile;
import s10k.node.sim.filter.domain.ExternalSettingsSummary;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.IgnoredComponent;
import s10k.node.sim.filter.domain.SettingsImport;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.registry.UnavailableFilterType;
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
 * The Mock, Modbus, and BACnet controls, Control Datum Sources, and Control
 * Conductors become simulated controls and Control Conductors for the
 * simulation's environment ({@link ControlSettings}), except in external
 * settings, which ignore them. They are imported even without their
 * {@code .FACTORY} rows, with a warning.
 * </p>
 *
 * <p>
 * A chain's {@code transformUids} become its {@code filterIds}. A Service Name
 * that is not in the file becomes an unknown filter, so the chain keeps it. The
 * Datum Queue's {@code transformServiceUid} gives the Main chain.
 * </p>
 *
 * <p>
 * Comment lines and blank rows are kept with the component whose row follows
 * them, or with the component before them at the end of the file. A comment
 * that is a commented-out row of a component, such as a setting the user
 * switched off, is kept with that component instead.
 * </p>
 *
 * <p>
 * Besides importing a file on its own, the importer can add a file to an
 * existing configuration ({@link #merge(SimConfig, String)}), like SolarNode
 * importing a file into its settings, and combine external settings files
 * ({@link #external(List)}), like SolarNode applying its auto settings files.
 * </p>
 *
 * @author matt
 * @version 1.1
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

	/** The ids of the configuration a file is added to, so new ids differ. */
	private final Set<String> usedIds = new HashSet<>(16);

	/** The unknown filters of the configuration a file is added to, by uid. */
	private final Map<String, FilterConfig> baseUnknowns = new LinkedHashMap<>(2);

	private @Nullable String baseDatumQueueComment;

	/** What the filters are in, for messages about filters that are not. */
	private String scope = "the file";

	/**
	 * {@code true} to apply rows add-only, like SolarNode's auto settings: a
	 * row is applied only if it has a value and the setting does not exist yet.
	 */
	private boolean addOnly;

	/** The name of the external settings file being applied. */
	private @Nullable String source;

	/** The name of the external settings file with the Datum Queue setting. */
	private @Nullable String mainSource;

	/** The components the file being applied ignores, with row counts. */
	private final Map<Component, Integer> fileIgnored = new LinkedHashMap<>(4);

	/** The filter instances the file being applied has rows for. */
	private final Set<Component> fileComponents = new LinkedHashSet<>(8);

	/** The control component instances, for the simulation's environment. */
	private final Map<Component, ControlInstance> controlInstances = new LinkedHashMap<>(4);

	/** A control component instance. */
	private static final class ControlInstance {

		private final Map<String, String> settings = new LinkedHashMap<>(8);
		private boolean factoryRow;

	}

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

		/** {@code true} if the instance has rows in a file. */
		private boolean touched;

		/** {@code true} if the instance is from the configuration added to. */
		private boolean seeded;

		/** The {@code id} to keep, or {@code null} for the default. */
		private @Nullable String configId;

		/** The instance key of a seeded instance, which can be missing. */
		private @Nullable String baseInstanceKey;

		/** The comment of a seeded instance, unless the file has one. */
		private @Nullable String baseComment;

		/** The external settings file that first has rows for the instance. */
		private @Nullable String source;

		/** The external settings file of each setting, by setting key. */
		private final Map<String, String> sources = new LinkedHashMap<>(8);

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
			final String id = configId;
			return (id != null ? id : factoryUid() + '.' + instanceKey());
		}

		private @Nullable String resultInstanceKey() {
			return (seeded ? baseInstanceKey : instanceKey());
		}

		private String label() {
			final String uid = settings.get(UID);
			return SimulationMessage.quoted(uid != null && !uid.isBlank() ? uid : id());
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
		if ( !applyFile(csv) ) {
			throw new SimulationException("SolarNode cannot import this settings CSV file.", errors);
		}
		return result();
	}

	/**
	 * Add a settings CSV file to a configuration, like SolarNode importing a
	 * file into its existing settings.
	 *
	 * <p>
	 * The configuration's filters become instances, with their member lists as
	 * {@code transformUids} settings, and the file's rows then apply to them as
	 * in {@link #importCsv(String)}: a row replaces the setting with the same
	 * key, and an empty value removes it. The configuration's filters keep
	 * their ids, and their comments unless the file has comments for them. An
	 * importer can import only one file.
	 * </p>
	 *
	 * @param base
	 *        the configuration to add the file to
	 * @param csv
	 *        the settings CSV file content
	 * @return the import result, with the combined configuration
	 * @throws SimulationException
	 *         if SolarNode cannot import the file
	 */
	SettingsImport merge(SimConfig base, String csv) {
		scope = "the file or the simulation";
		seed(base);
		return importCsv(csv);
	}

	/**
	 * Combine external settings files, like SolarNode applying the files in its
	 * {@code auto-settings.d} directory.
	 *
	 * <p>
	 * The files are applied in name order, ignoring case, and each row is
	 * applied add-only: only if it has a value and the setting does not exist
	 * yet. So the first file with a setting wins. Like SolarNode, a file it
	 * cannot import is skipped, with an error message. An importer can combine
	 * only one set of files.
	 * </p>
	 *
	 * @param files
	 *        the files
	 * @return the combined external settings
	 */
	ExternalSettings external(List<ExternalSettingsFile> files) {
		scope = "the external settings";
		addOnly = true;
		final List<ExternalSettingsFile> sorted = new ArrayList<>(files.size());
		for ( ExternalSettingsFile f : files ) {
			if ( f != null ) {
				sorted.add(f);
			}
		}
		sorted.sort(Comparator.comparing(f -> fileName(f), String.CASE_INSENSITIVE_ORDER));

		// the parts of a file summary known before the result
		record Pending(String name, int settingCount, boolean applied, List<Component> components,
				List<IgnoredComponent> ignored, List<SimulationMessage> messages) {

		}

		final List<Pending> pending = new ArrayList<>(sorted.size());
		for ( ExternalSettingsFile f : sorted ) {
			final String name = fileName(f);
			final String csv = f.csv();
			source = name;
			errors.clear();
			missingFactoryKeys.clear();
			fileIgnored.clear();
			fileComponents.clear();
			final int start = messages.size();
			final boolean applied = applyFile(csv != null ? csv : "");
			final List<SimulationMessage> fileMessages = new ArrayList<>(
					messages.subList(start, messages.size()));
			messages.subList(start, messages.size()).clear();
			fileMessages.addAll(errors);
			int settingCount = 0;
			for ( Line line : lines ) {
				if ( line instanceof SettingRow ) {
					settingCount++;
				}
			}
			final List<IgnoredComponent> ignoredComponents = new ArrayList<>(fileIgnored.size());
			for ( Map.Entry<Component, Integer> e : fileIgnored.entrySet() ) {
				ignoredComponents.add(
						new IgnoredComponent(e.getKey().key(), e.getKey().instanceKey(), e.getValue()));
			}
			pending.add(new Pending(name, settingCount, applied, List.copyOf(fileComponents),
					ignoredComponents, fileMessages));
		}
		source = null;
		errors.clear();

		final SettingsImport result = result();
		final List<ExternalSettingsSummary> summaries = new ArrayList<>(pending.size());
		for ( Pending p : pending ) {
			final List<String> filterIds = new ArrayList<>(p.components().size());
			for ( Component c : p.components() ) {
				final Instance instance = instances.get(c);
				if ( instance != null ) {
					filterIds.add(instance.id());
				}
			}
			summaries.add(new ExternalSettingsSummary(p.name(), p.settingCount(), p.applied(), filterIds,
					p.ignored(), p.messages()));
		}
		final Map<String, ExternalFilterOrigin> origins = new LinkedHashMap<>(instances.size());
		for ( Instance instance : instances.values() ) {
			final String file = instance.source;
			if ( file != null ) {
				origins.put(instance.id(), new ExternalFilterOrigin(file, Map.copyOf(instance.sources)));
			}
		}
		return new ExternalSettings(result.config(), summaries, origins, mainSource, result.messages());
	}

	private static String fileName(ExternalSettingsFile f) {
		final String name = f.name();
		return (name != null ? name : "");
	}

	/**
	 * Apply a settings CSV file.
	 *
	 * @param csv
	 *        the settings CSV file content
	 * @return {@code true} if the file was applied, or {@code false} if
	 *         SolarNode cannot import it, as described by {@code errors}
	 */
	private boolean applyFile(String csv) {
		final String text = (csv.startsWith("\uFEFF") ? csv.substring(1) : csv);
		checkHeader(text);
		lines.clear();
		read(text);
		if ( !errors.isEmpty() ) {
			return false;
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
		return true;
	}

	/**
	 * Make the filters of a configuration instances, before a file is added.
	 *
	 * @param base
	 *        the configuration
	 */
	private void seed(SimConfig base) {
		final Map<String, FilterConfig> byId = new LinkedHashMap<>(base.filtersOrEmpty().size());
		for ( FilterConfig f : base.filtersOrEmpty() ) {
			if ( f != null && f.id() != null && f.factoryUid() != null ) {
				byId.putIfAbsent(f.id(), f);
			}
		}
		for ( FilterConfig f : byId.values() ) {
			usedIds.add(f.id());
			if ( f.isUnknown() ) {
				final String uid = f.settingsOrEmpty().get(UID);
				if ( uid != null && !uid.isBlank() ) {
					baseUnknowns.putIfAbsent(uid, f);
				}
				continue;
			}
			final String key = f.instanceKey();
			Component c = new Component(f.factoryUid(), key != null && !key.isEmpty() ? key : null);
			if ( c.instanceKey() == null || instances.containsKey(c) ) {
				// without its own instance key, no row of the file can apply to it
				c = new Component(f.factoryUid(), "\u0000" + f.id());
			}
			final Instance instance = new Instance(c);
			instance.seeded = true;
			instance.factoryRow = true;
			instance.configId = f.id();
			instance.baseInstanceKey = key;
			instance.baseComment = f.comment();
			final boolean chain = StandardFilterTypes.CHAIN.equals(f.factoryUid());
			for ( Map.Entry<String, String> e : f.settingsOrEmpty().entrySet() ) {
				if ( e.getKey() != null && e.getValue() != null
						&& !(chain && e.getKey().startsWith(CHAIN_FILTERS)) ) {
					instance.settings.put(e.getKey(), e.getValue());
				}
			}
			for ( Map.Entry<String, String> e : f.notesOrEmpty().entrySet() ) {
				if ( e.getKey() != null && e.getValue() != null ) {
					instance.notes.put(e.getKey(), e.getValue());
				}
			}
			if ( chain && f.filterIds() != null ) {
				// the members as transformUids settings, so the file's rows can change them
				final List<@Nullable String> members = f.filterIdsOrEmpty();
				instance.settings.put(CHAIN_FILTERS_COUNT, String.valueOf(members.size()));
				for ( int i = 0; i < members.size(); i++ ) {
					// Unassigned slots have no row
					final String memberId = members.get(i);
					final FilterConfig member = (memberId != null ? byId.get(memberId) : null);
					final String uid = (member != null ? member.settingsOrEmpty().get(UID) : null);
					if ( uid != null && !uid.isBlank() ) {
						instance.settings.put(CHAIN_FILTERS + '[' + i + ']', uid);
					}
				}
			}
			instances.put(c, instance);
		}
		final String mainId = base.mainChainId();
		final FilterConfig main = (mainId != null ? byId.get(mainId) : null);
		if ( main != null ) {
			final String uid = main.settingsOrEmpty().get(UID);
			if ( uid != null && !uid.isBlank() ) {
				mainUid = uid;
			}
		}
		baseDatumQueueComment = base.datumQueueComment();
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
		if ( addOnly && row.value() == null ) {
			// like SolarNode's auto settings, a row without a value is not imported
			return discardedComment;
		}
		if ( key.regionMatches(true, 0, INTERNAL_PREFIX, 0, INTERNAL_PREFIX.length()) ) {
			return ignore(new Component(key, null));
		}
		if ( key.endsWith(FACTORY_SUFFIX) ) {
			final Component c = new Component(key.substring(0, key.length() - FACTORY_SUFFIX.length()),
					row.type());
			if ( !row.type().isEmpty() && isControlType(c.key()) ) {
				if ( row.value() != null ) {
					controlInstances.computeIfAbsent(c, _ -> new ControlInstance()).factoryRow = true;
				}
				return discardedComment;
			}
			if ( row.type().isEmpty() || !isFilterType(c.key()) ) {
				return ignore(c);
			}
			if ( row.value() == null ) {
				// the row removes the instance
				final Instance instance = instances.get(c);
				return (instance != null ? instance.comment : ignore(c));
			}
			final Instance instance = instances.computeIfAbsent(c, Instance::new);
			touch(instance);
			instance.factoryRow = true;
			return instance.comment;
		}
		final Component c = controlComponent(component(key));
		final String instanceKey = c.instanceKey();
		if ( instanceKey != null && isControlType(c.key()) ) {
			applyControl(c, row);
			return discardedComment;
		}
		if ( instanceKey == null || !isFilterType(c.key()) ) {
			if ( DATUM_QUEUE_KEY.equals(key) ) {
				if ( DATUM_QUEUE_FILTER.equals(row.type()) ) {
					if ( !addOnly || mainUid == null ) {
						mainUid = row.value();
						mainSource = source;
					}
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
		touch(instance);
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
		} else if ( addOnly && instance.settings.containsKey(row.type()) ) {
			// like SolarNode's auto settings, an existing setting is kept
			return instance.comment;
		} else {
			instance.settings.put(row.type(), row.value());
			if ( row.note() != null ) {
				instance.notes.put(row.type(), row.note());
			} else {
				instance.notes.remove(row.type());
			}
			final String file = source;
			if ( file != null ) {
				instance.sources.put(row.type(), file);
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
		return registry.isFilterType(factoryUid);
	}

	/**
	 * Test if a component is a control component, for the simulation's
	 * environment.
	 *
	 * <p>
	 * Only a file imported on its own or added to a configuration has them, not
	 * external settings.
	 * </p>
	 *
	 * @param factoryUid
	 *        the factory UID
	 * @return {@code true} if it is a control component
	 */
	private boolean isControlType(String factoryUid) {
		return (!addOnly && ControlSettings.FACTORY_UIDS.contains(factoryUid));
	}

	/**
	 * Make a key of a control component without a {@code .FACTORY} row an
	 * instance of it.
	 *
	 * @param c
	 *        the component
	 * @return the control component instance, or else {@code c}
	 */
	private Component controlComponent(Component c) {
		if ( c.instanceKey() != null || addOnly ) {
			return c;
		}
		final String factoryUid = ControlSettings.factoryUid(c.key());
		return (factoryUid != null
				? new Component(factoryUid, c.key().substring(factoryUid.length() + 1))
				: c);
	}

	/**
	 * Apply a setting row of a control component instance.
	 *
	 * @param c
	 *        the instance
	 * @param row
	 *        the row
	 */
	private void applyControl(Component c, SettingRow row) {
		final ControlInstance instance = controlInstances.computeIfAbsent(c, _ -> new ControlInstance());
		if ( row.type().isEmpty() || SETUP_TYPE.equals(row.type()) ) {
			return;
		}
		if ( row.value() == null ) {
			instance.settings.remove(row.type());
		} else {
			instance.settings.put(row.type(), row.value());
		}
	}

	private List<String> ignore(Component c) {
		ignored.merge(c, 1, Integer::sum);
		fileIgnored.merge(c, 1, Integer::sum);
		return discardedComment;
	}

	/**
	 * Note that the file being applied has a row for an instance.
	 *
	 * @param instance
	 *        the instance
	 */
	private void touch(Instance instance) {
		instance.touched = true;
		fileComponents.add(instance.component);
		if ( instance.source == null ) {
			instance.source = source;
		}
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
		final List<UnavailableFilterType> unavailable = registry.unavailableTypes();
		final List<String> result = new ArrayList<>(registry.definitions().size() + unavailable.size());
		registry.definitions().forEach(d -> result.add(d.factoryUid()));
		unavailable.forEach(u -> result.add(u.factoryUid()));
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
			c = controlComponent(component(key));
		}
		final Instance instance = instances.get(c);
		if ( instance != null ) {
			return instance.comment;
		}
		return (ignored.containsKey(c) || controlInstances.containsKey(c) ? discardedComment : null);
	}

	private SettingsImport result() {
		// a new instance's id must not be one the configuration added to already uses
		for ( Instance instance : instances.values() ) {
			if ( instance.configId == null && usedIds.contains(instance.id()) ) {
				final String id = instance.id();
				int n = 2;
				while ( usedIds.contains(id + '-' + n) ) {
					n++;
				}
				instance.configId = id + '-' + n;
			}
			usedIds.add(instance.id());
		}

		final Map<String, String> idsByUid = new LinkedHashMap<>(instances.size());
		final Map<String, List<String>> duplicateUids = new LinkedHashMap<>(2);
		final Map<String, List<@Nullable String>> chainUids = new LinkedHashMap<>(4);
		for ( Instance instance : instances.values() ) {
			if ( instance.touched ) {
				instanceMessages(instance);
			}
			if ( StandardFilterTypes.CHAIN.equals(instance.factoryUid()) ) {
				final List<@Nullable String> members = chainMembers(instance);
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
							.formatted(SimulationMessage.quoted(e.getKey()), e.getValue().size(),
									SimulationMessage.quoted(e.getValue())),
					e.getValue().getFirst(), null));
		}

		final List<FilterConfig> filters = new ArrayList<>(instances.size() + 2);
		final Map<String, FilterConfig> unknowns = new LinkedHashMap<>(2);
		// the configuration's unknown filters are kept, without a message
		for ( Map.Entry<String, FilterConfig> e : baseUnknowns.entrySet() ) {
			if ( !idsByUid.containsKey(e.getKey()) ) {
				unknowns.put(e.getKey(), e.getValue());
			}
		}
		for ( Instance instance : instances.values() ) {
			final List<@Nullable String> uids = chainUids.get(instance.id());
			List<@Nullable String> filterIds = null;
			if ( uids != null ) {
				filterIds = new ArrayList<>(uids.size());
				for ( String uid : uids ) {
					if ( uid == null ) {
						// an Unassigned slot
						filterIds.add(null);
						continue;
					}
					String memberId = idsByUid.get(uid);
					if ( memberId == null ) {
						FilterConfig unknown = unknowns.get(uid);
						if ( unknown == null ) {
							unknown = new FilterConfig(FilterConfig.UNKNOWN_FACTORY_UID + ':' + uid,
									FilterConfig.UNKNOWN_FACTORY_UID, null, Map.of(UID, uid), null);
							unknowns.put(uid, unknown);
							messages.add(warning(SimulationMessage.CODE_UNKNOWN_FILTER,
									"The chain %s uses the filter %s, which is not in %s. It is kept as an unknown filter, which datum pass through unchanged."
											.formatted(instance.label(), SimulationMessage.quoted(uid),
													scope),
									unknown.id(), null));
						}
						memberId = unknown.id();
					}
					filterIds.add(memberId);
				}
			}
			final String comment = comment(instance.comment);
			filters.add(new FilterConfig(instance.id(), instance.factoryUid(),
					instance.resultInstanceKey(), instance.settings, filterIds,
					comment != null ? comment : instance.baseComment,
					instance.notes.isEmpty() ? null : instance.notes));
		}
		filters.addAll(unknowns.values());

		String mainChainId = null;
		if ( mainUid != null ) {
			mainChainId = idsByUid.get(mainUid);
			if ( mainChainId == null ) {
				messages.add(warning(SimulationMessage.CODE_MAIN_CHAIN,
						"The Datum Queue uses the filter %s, which is not in %s."
								.formatted(SimulationMessage.quoted(mainUid), scope),
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
		final List<ControlSettings.Instance> controlComponents = new ArrayList<>(
				controlInstances.size());
		for ( Map.Entry<Component, ControlInstance> e : controlInstances.entrySet() ) {
			final String instanceKey = e.getKey().instanceKey();
			controlComponents.add(new ControlSettings.Instance(e.getKey().key(),
					instanceKey != null ? instanceKey : "", e.getValue().settings,
					e.getValue().factoryRow));
		}
		final ControlSettings.Result controls = ControlSettings.of(controlComponents);
		messages.addAll(controls.messages());

		final String dqComment = comment(datumQueueComment);
		return new SettingsImport(
				new SimConfig(filters, mainChainId,
						dqComment != null ? dqComment : baseDatumQueueComment),
				messages, ignoredComponents, controls.controls().isEmpty() ? null : controls.controls(),
				controls.conductors().isEmpty() ? null : controls.conductors());
	}

	private void instanceMessages(Instance instance) {
		final UnavailableFilterType unavailable = registry.unavailable(instance.factoryUid());
		if ( unavailable != null ) {
			messages.add(info(SimulationMessage.CODE_UNSUPPORTED_FILTER,
					"The %s %s is not available, so datum pass through it unchanged. Its settings are kept as they are. %s"
							.formatted(unavailable.name(), instance.label(), unavailable.reason()),
					instance.id()));
		}
		final String problem = InstanceIds.problem(instance.instanceKey());
		if ( instance.skipped ) {
			messages.add(warning(SimulationMessage.CODE_INVALID_INSTANCE_ID,
					"SolarNode skips the settings of the filter %s, because its instance ID %s is not allowed. Change the instance ID before you export the settings."
							.formatted(instance.label(),
									SimulationMessage.quoted(instance.instanceKey())),
					instance.id(), null));
		} else if ( problem != null ) {
			messages.add(warning(SimulationMessage.CODE_INVALID_INSTANCE_ID,
					"The instance ID %s of the filter %s is not allowed: %s Change it before you export the settings."
							.formatted(SimulationMessage.quoted(instance.instanceKey()),
									instance.label(), problem),
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
	 * The members are positional, like SolarNode's array of them: the item at
	 * each index is the {@code transformUids} setting with that index, and an
	 * index without a setting, with a blank one, or with the
	 * {@link FilterConfig#UNASSIGNED_UID} is a {@code null} Unassigned slot.
	 * Like the chain's {@code transformUidsCount} setting, only that many
	 * members are used, and without one, the members end at the last member.
	 * When applying External Settings, the count setting is kept, so an export
	 * can tell whether the files have one.
	 * </p>
	 *
	 * @param chain
	 *        the chain
	 * @return the members, or {@code null} if the chain has no
	 *         {@code transformUids} settings
	 */
	private @Nullable List<@Nullable String> chainMembers(Instance chain) {
		boolean found = false;
		Integer count = null;
		String countValue = null;
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
				countValue = e.getValue();
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
		if ( addOnly && countValue != null ) {
			chain.settings.put(CHAIN_FILTERS_COUNT, countValue);
		}
		int size = 0;
		for ( Map.Entry<Integer, String> e : uids.entrySet() ) {
			if ( count != null && e.getKey() >= count ) {
				messages.add(warning(SimulationMessage.CODE_INVALID_SETTING,
						"The chain %s has a %s[%d] setting, but its %s is %d, so it is ignored."
								.formatted(chain.label(), CHAIN_FILTERS, e.getKey(), CHAIN_FILTERS_COUNT,
										count),
						chain.id(), null));
			} else if ( isMember(e.getValue()) ) {
				size = e.getKey() + 1;
			}
		}
		if ( count != null && count > 0 ) {
			size = count;
		}
		final List<@Nullable String> result = new ArrayList<>(size);
		for ( int i = 0; i < size; i++ ) {
			final String uid = uids.get(i);
			result.add(isMember(uid) ? uid : null);
		}
		return result;
	}

	/**
	 * Test if a {@code transformUids} value is a member, rather than an
	 * Unassigned slot.
	 *
	 * @param uid
	 *        the value
	 * @return {@code true} if the value is a Service Name of a filter
	 */
	private static boolean isMember(@Nullable String uid) {
		return (uid != null && !uid.isBlank() && !FilterConfig.UNASSIGNED_UID.equals(uid));
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
