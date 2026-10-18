/* ==================================================================
 * SettingsCsvExporter.java - 18 Oct 2026 1:03:29 pm
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

import static s10k.node.sim.filter.csv.SettingsCsv.CHAIN_FILTERS;
import static s10k.node.sim.filter.csv.SettingsCsv.DATUM_QUEUE_FILTER;
import static s10k.node.sim.filter.csv.SettingsCsv.DATUM_QUEUE_KEY;
import static s10k.node.sim.filter.csv.SettingsCsv.FACTORY_SUFFIX;
import static s10k.node.sim.filter.csv.SettingsCsv.HEADER;
import static s10k.node.sim.filter.csv.SettingsCsv.MAIN_INSTANCE_KEY;
import static s10k.node.sim.filter.csv.SettingsCsv.UID;
import static s10k.node.sim.filter.domain.SimulationMessage.error;
import static s10k.node.sim.filter.domain.SimulationMessage.info;
import static s10k.node.sim.filter.domain.SimulationMessage.warning;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import de.siegmar.fastcsv.writer.CsvWriter;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.SettingField;
import s10k.node.sim.filter.domain.SettingsExport;
import s10k.node.sim.filter.domain.SettingsExportRequest.Style;
import s10k.node.sim.filter.domain.SettingsFormRequest;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.form.SettingsFormService;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.settings.SettingsApplier;
import s10k.node.sim.filter.sim.SimulationException;

/**
 * Exports a filter configuration as a SolarNode settings CSV file.
 *
 * <p>
 * Each filter is a section of setting rows followed by its {@code .FACTORY}
 * row, and the Main chain's Service Name is the Datum Queue's
 * {@code transformServiceUid} setting. A chain's {@code transformUids} come
 * from its {@code filterIds}, using each member's Service Name, unless its
 * {@code filterIds} is {@code null}. External filters have no rows, and
 * transient settings are left out, because SolarNode does not save them.
 * </p>
 *
 * <p>
 * The {@link Style#Organized} style writes the Datum Queue first, then the Main
 * chain and the filters it runs, depth-first through nested chains, then the
 * other filters. Each section starts with its comment lines, or a generated
 * banner, and lists the filter's settings in the order of its settings form.
 * The {@link Style#Plain} style sorts the rows by key and type, without
 * comments, like a SolarNode export.
 * </p>
 *
 * <p>
 * Filters without an instance ID get one, like new SolarNode components. An
 * instance ID that SolarNode does not allow, or that another filter of the same
 * type has, prevents the export.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
final class SettingsCsvExporter {

	/** The locale of generated banners and forms. */
	private static final Locale LOCALE = Locale.ENGLISH;

	/** A line of a comment that is a blank row: only commas and spaces. */
	private static final Pattern BLANK_ROW = Pattern.compile("[,\\s]*");

	/**
	 * The empty columns at the end of a comment line, which spreadsheet apps
	 * add.
	 */
	private static final Pattern TRAILING_EMPTY_COLUMNS = Pattern.compile("[,\\s]+$");

	private static final String DATUM_QUEUE_TITLE = "Datum Queue";

	private final FilterTypeRegistry registry;
	private final SettingsFormService forms;
	private final List<SimulationMessage> messages = new ArrayList<>(8);
	private final List<SimulationMessage> errors = new ArrayList<>(2);

	/**
	 * A section of an exported file.
	 *
	 * @param comment
	 *        the comment lines, each a {@code #} comment or an empty string for
	 *        a blank row
	 * @param rows
	 *        the setting rows
	 */
	private record Section(List<String> comment, List<String[]> rows) {

	}

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param forms
	 *        the settings form service, for the order and kinds of settings
	 */
	SettingsCsvExporter(FilterTypeRegistry registry, SettingsFormService forms) {
		super();
		this.registry = registry;
		this.forms = forms;
	}

	/**
	 * Export a filter configuration.
	 *
	 * <p>
	 * An exporter can export only one configuration.
	 * </p>
	 *
	 * @param config
	 *        the configuration
	 * @param style
	 *        the file style
	 * @return the export
	 * @throws SimulationException
	 *         if the configuration cannot be exported
	 */
	SettingsExport export(SimConfig config, Style style) {
		final Map<String, FilterConfig> configs = configs(config);
		final Map<String, String> instanceKeys = instanceKeys(configs, config.mainChainId());
		if ( !errors.isEmpty() ) {
			throw new SimulationException("The filter configuration cannot be exported.", errors);
		}
		checkUids(configs);

		final List<Section> sections = new ArrayList<>(configs.size() + 1);
		final Section datumQueue = datumQueueSection(config, configs);
		if ( datumQueue != null ) {
			sections.add(datumQueue);
		}
		for ( FilterConfig c : order(configs, config.mainChainId()) ) {
			final String instanceKey = instanceKeys.get(c.id());
			if ( instanceKey != null ) {
				sections.add(filterSection(c, instanceKey, configs));
			}
		}
		return new SettingsExport(write(sections, style), messages);
	}

	private Map<String, FilterConfig> configs(SimConfig config) {
		final Map<String, FilterConfig> result = new LinkedHashMap<>(config.filtersOrEmpty().size());
		for ( FilterConfig c : config.filtersOrEmpty() ) {
			if ( c == null || c.id() == null || c.id().isBlank() ) {
				errors.add(error(SimulationMessage.CODE_INVALID_CONFIG,
						"Every filter configuration must have an id.", null));
			} else if ( c.factoryUid() == null || c.factoryUid().isBlank() ) {
				errors.add(error(SimulationMessage.CODE_INVALID_CONFIG,
						"The filter %s has no factoryUid.".formatted(c.id()), c.id()));
			} else if ( result.putIfAbsent(c.id(), c) != null ) {
				errors.add(error(SimulationMessage.CODE_INVALID_CONFIG,
						"More than one filter configuration has the id %s.".formatted(c.id()), c.id()));
			}
		}
		return result;
	}

	/**
	 * Check and assign instance IDs.
	 *
	 * @param configs
	 *        the filter configurations
	 * @param mainId
	 *        the Main chain ID
	 * @return the instance ID of each filter configuration ID, without external
	 *         filters
	 */
	private Map<String, String> instanceKeys(Map<String, FilterConfig> configs,
			@Nullable String mainId) {
		final Map<String, Set<String>> used = new HashMap<>(8);
		final Map<String, String> result = new LinkedHashMap<>(configs.size());
		for ( FilterConfig c : configs.values() ) {
			final String key = c.instanceKey();
			if ( c.isExternal() || key == null || key.isEmpty() ) {
				continue;
			}
			final String problem = InstanceIds.problem(key);
			if ( problem != null ) {
				errors.add(error(SimulationMessage.CODE_INVALID_INSTANCE_ID,
						"The instance ID %s of the filter %s is not allowed: %s".formatted(key, label(c),
								problem),
						c.id()));
			} else if ( !used.computeIfAbsent(c.factoryUid(), _ -> new LinkedHashSet<>(4)).add(key) ) {
				errors.add(error(SimulationMessage.CODE_DUPLICATE_INSTANCE_ID,
						"The instance ID %s of the filter %s is used by another filter of the same type."
								.formatted(key, label(c)),
						c.id()));
			} else {
				result.put(c.id(), key);
			}
		}
		for ( FilterConfig c : configs.values() ) {
			final String key = c.instanceKey();
			if ( c.isExternal() || (key != null && !key.isEmpty()) ) {
				continue;
			}
			final Set<String> keys = used.computeIfAbsent(c.factoryUid(), _ -> new LinkedHashSet<>(4));
			final String assigned = (c.id().equals(mainId) && !keys.contains(MAIN_INSTANCE_KEY)
					? MAIN_INSTANCE_KEY
					: InstanceIds.next(keys));
			keys.add(assigned);
			result.put(c.id(), assigned);
			messages.add(info(SimulationMessage.CODE_INSTANCE_ID_ASSIGNED,
					"The filter %s has no instance ID, so it is exported as %s.".formatted(label(c),
							assigned),
					c.id()));
		}
		final List<String> legacy = result.values().stream()
				.filter(k -> !InstanceIds.isLegacyCompatible(k)).distinct().toList();
		if ( !legacy.isEmpty() ) {
			messages.add(warning(SimulationMessage.CODE_LEGACY_INSTANCE_ID,
					"SolarNode releases before Core Settings Support 4.2.1 do not import the settings of instance IDs with characters other than A-Z, a-z, and 0-9: %s."
							.formatted(String.join(", ", legacy)),
					null, null));
		}
		return result;
	}

	private void checkUids(Map<String, FilterConfig> configs) {
		final Map<String, List<String>> idsByUid = new LinkedHashMap<>(configs.size());
		for ( FilterConfig c : configs.values() ) {
			final String uid = uid(c);
			if ( uid != null && !c.isExternal() ) {
				idsByUid.computeIfAbsent(uid, _ -> new ArrayList<>(2)).add(c.id());
			}
		}
		for ( Map.Entry<String, List<String>> e : idsByUid.entrySet() ) {
			if ( e.getValue().size() > 1 ) {
				messages.add(warning(SimulationMessage.CODE_DUPLICATE_UID,
						"The Service Name %s is used by %d filters (%s), so filter chains use only the first one."
								.formatted(e.getKey(), e.getValue().size(),
										String.join(", ", e.getValue())),
						e.getValue().getFirst(), null));
			}
		}
	}

	private @Nullable Section datumQueueSection(SimConfig config, Map<String, FilterConfig> configs) {
		final String mainId = config.mainChainId();
		if ( mainId == null ) {
			return null;
		}
		final FilterConfig main = configs.get(mainId);
		if ( main == null ) {
			messages.add(warning(SimulationMessage.CODE_MAIN_CHAIN,
					"The Main chain %s does not exist, so the Datum Queue setting is not exported."
							.formatted(mainId),
					null, null));
			return null;
		}
		final String uid = uid(main);
		if ( uid == null ) {
			messages.add(warning(SimulationMessage.CODE_MAIN_CHAIN,
					"The Main chain %s has no Service Name, so the Datum Queue setting is not exported."
							.formatted(main.id()),
					main.id(), null));
			return null;
		}
		final List<String[]> rows = new ArrayList<>(1);
		rows.add(row(DATUM_QUEUE_KEY, DATUM_QUEUE_FILTER, uid, null));
		final List<String> comment = commentLines(config.datumQueueComment());
		return new Section(comment.isEmpty() ? banner(DATUM_QUEUE_TITLE) : comment, rows);
	}

	/**
	 * Get the filters in section order: the Main chain and the filters it runs,
	 * depth-first through nested chains, then the other supported filters (each
	 * chain followed by its filters), then the other unsupported filters.
	 *
	 * @param configs
	 *        the filter configurations
	 * @param mainId
	 *        the Main chain ID
	 * @return the filters, without external filters
	 */
	private List<FilterConfig> order(Map<String, FilterConfig> configs, @Nullable String mainId) {
		final Set<String> visited = new LinkedHashSet<>(configs.size());
		if ( mainId != null ) {
			visit(mainId, configs, visited);
		}
		for ( FilterConfig c : configs.values() ) {
			if ( registry.definition(c.factoryUid()) != null ) {
				visit(c.id(), configs, visited);
			}
		}
		visited.addAll(configs.keySet());
		final List<FilterConfig> result = new ArrayList<>(visited.size());
		for ( String id : visited ) {
			final FilterConfig c = configs.get(id);
			if ( c != null && !c.isExternal() ) {
				result.add(c);
			}
		}
		return result;
	}

	private void visit(String id, Map<String, FilterConfig> configs, Set<String> visited) {
		final FilterConfig c = configs.get(id);
		if ( c == null || !visited.add(id) ) {
			return;
		}
		if ( StandardFilterTypes.CHAIN.equals(c.factoryUid()) ) {
			for ( String memberId : c.filterIdsOrEmpty() ) {
				if ( memberId != null ) {
					visit(memberId, configs, visited);
				}
			}
		}
	}

	private Section filterSection(FilterConfig c, String instanceKey,
			Map<String, FilterConfig> configs) {
		final FilterTypeDefinition def = registry.definition(c.factoryUid());
		final Map<String, String> settings = new LinkedHashMap<>(c.settingsOrEmpty().size());
		for ( Map.Entry<String, String> e : c.settingsOrEmpty().entrySet() ) {
			if ( e.getKey() != null && e.getValue() != null ) {
				settings.put(e.getKey(), e.getValue());
			}
		}
		if ( def != null && def.isChain() ) {
			settings.keySet().removeIf(k -> k.startsWith(CHAIN_FILTERS));
			if ( c.filterIds() != null ) {
				settings.putAll(chainFilters(c, configs));
			}
		}

		// order the settings like the settings form, and leave out transient settings
		final List<String> keys = new ArrayList<>(settings.size());
		final Set<String> transientKeys = new LinkedHashSet<>(2);
		if ( def != null ) {
			collectKeys(forms.form(new SettingsFormRequest(c.factoryUid(), settings), LOCALE).fields(),
					keys, transientKeys);
		}
		final Set<String> order = new LinkedHashSet<>(keys);
		order.addAll(settings.keySet());

		final String componentKey = c.factoryUid() + '.' + instanceKey;
		final List<String[]> rows = new ArrayList<>(settings.size() + 1);
		final List<String> leftOut = new ArrayList<>(2);
		for ( String key : order ) {
			final String value = settings.get(key);
			if ( value == null ) {
				continue;
			}
			if ( transientKeys.contains(key) ) {
				leftOut.add(key);
				continue;
			}
			rows.add(row(componentKey, key, value, c.notesOrEmpty().get(key)));
		}
		rows.add(row(c.factoryUid() + FACTORY_SUFFIX, instanceKey, instanceKey, null));
		if ( !leftOut.isEmpty() ) {
			messages.add(warning(SimulationMessage.CODE_TRANSIENT_SETTING,
					"The filter %s settings %s are not exported, because SolarNode does not save them."
							.formatted(label(c), String.join(", ", leftOut)),
					c.id(), null));
		}

		final List<String> comment = commentLines(c.comment());
		if ( !comment.isEmpty() ) {
			return new Section(comment, rows);
		}
		final String type = (def != null ? def.info(LOCALE).name()
				: StandardFilterTypes.UNSUPPORTED.getOrDefault(c.factoryUid(), c.factoryUid()));
		final String uid = uid(c);
		return new Section(banner(type + ": " + (uid != null ? uid : instanceKey)), rows);
	}

	/**
	 * Get a chain's {@code transformUids} settings.
	 *
	 * @param chain
	 *        the chain
	 * @param configs
	 *        the filter configurations
	 * @return the settings
	 */
	private Map<String, String> chainFilters(FilterConfig chain, Map<String, FilterConfig> configs) {
		final List<String> uids = new ArrayList<>(chain.filterIdsOrEmpty().size());
		for ( String memberId : chain.filterIdsOrEmpty() ) {
			final FilterConfig member = (memberId != null ? configs.get(memberId) : null);
			if ( member == null ) {
				messages.add(warning(SimulationMessage.CODE_UNKNOWN_CHAIN_MEMBER,
						"The chain %s refers to the filter %s, which does not exist, so it is left out."
								.formatted(label(chain), memberId),
						chain.id(), null));
				continue;
			}
			final String uid = uid(member);
			if ( uid == null ) {
				messages.add(warning(SimulationMessage.CODE_MISSING_UID,
						"The filter %s in the chain %s has no Service Name, so the chain cannot use it."
								.formatted(member.id(), label(chain)),
						member.id(), null));
				continue;
			}
			uids.add(uid);
		}
		final Map<String, String> result = new LinkedHashMap<>(uids.size() + 1);
		result.put(CHAIN_FILTERS + SettingsApplier.COUNT_SUFFIX, String.valueOf(uids.size()));
		for ( int i = 0; i < uids.size(); i++ ) {
			result.put(CHAIN_FILTERS + '[' + i + ']', uids.get(i));
		}
		return result;
	}

	/**
	 * Collect the setting keys of form fields, in order.
	 *
	 * @param fields
	 *        the fields
	 * @param keys
	 *        the keys, including the item count key of each dynamic list
	 * @param transientKeys
	 *        the keys of transient settings
	 */
	private static void collectKeys(List<SettingField> fields, List<String> keys,
			Set<String> transientKeys) {
		for ( SettingField f : fields ) {
			if ( f.type() == SettingField.Type.Group ) {
				if ( f.countKey() != null ) {
					keys.add(f.countKey());
				}
				if ( f.fields() != null ) {
					collectKeys(f.fields(), keys, transientKeys);
				}
			} else if ( f.key() != null ) {
				keys.add(f.key());
				if ( f.transientSetting() ) {
					transientKeys.add(f.key());
				}
			}
		}
	}

	private static String[] row(String key, String type, String value, @Nullable String note) {
		return new String[] { key, type, value, "", "", note != null ? note : "" };
	}

	private static @Nullable String uid(FilterConfig c) {
		final String uid = c.settingsOrEmpty().get(UID);
		return (uid != null && !uid.isBlank() ? uid : null);
	}

	private static String label(FilterConfig c) {
		final String uid = uid(c);
		return (uid != null ? uid : c.id());
	}

	/**
	 * Get the lines of a comment.
	 *
	 * <p>
	 * Lines that are neither {@code #} comments nor blank rows become comments,
	 * so a comment cannot add settings. The empty columns at the end of a
	 * comment line, like the {@code ,,,,,} that spreadsheet apps add, are
	 * removed, blank rows become empty lines, and leading and trailing blank
	 * rows are removed.
	 * </p>
	 *
	 * @param comment
	 *        the comment
	 * @return the lines, each a {@code #} comment or an empty string for a
	 *         blank row, or an empty list if there are no comment lines
	 */
	static List<String> commentLines(@Nullable String comment) {
		if ( comment == null || comment.isBlank() ) {
			return List.of();
		}
		final List<String> lines = new ArrayList<>(8);
		for ( String line : comment.split("\\r\\n|\\r|\\n", -1) ) {
			if ( line.startsWith("#") ) {
				lines.add(TRAILING_EMPTY_COLUMNS.matcher(line).replaceFirst(""));
			} else if ( BLANK_ROW.matcher(line).matches() ) {
				lines.add("");
			} else {
				lines.add("# " + line);
			}
		}
		int start = 0;
		int end = lines.size();
		while ( start < end && !lines.get(start).startsWith("#") ) {
			start++;
		}
		while ( end > start && !lines.get(end - 1).startsWith("#") ) {
			end--;
		}
		return lines.subList(start, end);
	}

	private static List<String> banner(String title) {
		final String line = "# " + title;
		final String rule = "#".repeat(line.length());
		return List.of(rule, line, rule);
	}

	private static String write(List<Section> sections, Style style) {
		final StringWriter out = new StringWriter(4096);
		try (CsvWriter writer = CsvWriter.builder().build(out)) {
			writer.writeRecord(HEADER);
			if ( style == Style.Plain ) {
				final List<String[]> rows = new ArrayList<>(64);
				for ( Section s : sections ) {
					rows.addAll(s.rows());
				}
				rows.sort(Comparator.comparing((String[] r) -> r[0]).thenComparing(r -> r[1]));
				for ( String[] row : rows ) {
					writer.writeRecord(row);
				}
			} else {
				for ( Section s : sections ) {
					writer.writeRecord("");
					if ( !s.comment().isEmpty() ) {
						for ( String line : s.comment() ) {
							if ( line.startsWith("#") ) {
								writer.writeComment(line.substring(1));
							} else {
								writer.writeRecord("");
							}
						}
						writer.writeRecord("");
					}
					for ( String[] row : s.rows() ) {
						writer.writeRecord(row);
					}
				}
			}
		} catch ( IOException e ) {
			throw new UncheckedIOException(e);
		}
		return out.toString();
	}

}
