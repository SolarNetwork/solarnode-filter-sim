/* ==================================================================
 * SettingsCsvServiceTests.java - 18 Oct 2026 1:03:29 pm
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

package s10k.node.sim.filter.csv.test;

import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.groups.Tuple.tuple;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import s10k.node.sim.filter.csv.SettingsCsvService;
import s10k.node.sim.filter.domain.ControlConductorConfig;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.DatumSourceConfig;
import s10k.node.sim.filter.domain.ExternalFilterOrigin;
import s10k.node.sim.filter.domain.ExternalSettings;
import s10k.node.sim.filter.domain.ExternalSettingsFile;
import s10k.node.sim.filter.domain.ExternalSettingsRequest;
import s10k.node.sim.filter.domain.ExternalSettingsSummary;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.IgnoredComponent;
import s10k.node.sim.filter.domain.NodeControlConfig;
import s10k.node.sim.filter.domain.SettingsExport;
import s10k.node.sim.filter.domain.SettingsExportRequest;
import s10k.node.sim.filter.domain.SettingsExportRequest.Style;
import s10k.node.sim.filter.domain.SettingsImport;
import s10k.node.sim.filter.domain.SettingsMergeRequest;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.form.SettingsFormService;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationException;
import net.solarnetwork.common.expr.spel.SpelExpressionService;

/**
 * Test cases for the {@link SettingsCsvService} class.
 *
 * <p>
 * The {@code daily-limit-tracking}, {@code main-filter-chain}, and
 * {@code peak-tracking} files are the SolarNode Handbook recipes, which are
 * SolarNode exports. The {@code commented} file is organized by hand with
 * comment banners. The {@code 0010-solarnode-config-*} files are the auto
 * settings files of the {@code solarnode-config-*} packages in
 * {@code solarnode-os-packages}.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public class SettingsCsvServiceTests {

	private static final String MAIN_ID = StandardFilterTypes.CHAIN + ".Main";

	private static final String HEADER = "key,type,value,flags,modified,note\r\n";

	private SettingsCsvService service;

	@BeforeEach
	public void setup() {
		FilterTypeRegistry registry = new FilterTypeRegistry(StandardFilterTypes.definitions());
		service = new SettingsCsvService(registry,
				new SettingsFormService(registry, new SpelExpressionService()));
	}

	private static String resource(String name) {
		try (InputStream in = SettingsCsvServiceTests.class.getResourceAsStream(name)) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch ( IOException e ) {
			throw new UncheckedIOException(e);
		}
	}

	private static FilterConfig filter(SimConfig config, String id) {
		return config.filtersOrEmpty().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
	}

	private SettingsExport export(SimConfig config, Style style) {
		return service.exportCsv(new SettingsExportRequest(config, style));
	}

	private static final String DATUM_FILTERS_PACKAGE = "0010-solarnode-config-datumfilters.csv";

	private static final String PING_PACKAGE = "0100-solarnode-config-ping.csv";

	private static final String SETUP_LIVE_PACKAGE = "0200-solarnode-config-setup-live.csv";

	/**
	 * Get a settings CSV file of rows, after the header.
	 */
	private static String csv(String... rows) {
		return HEADER + String.join("\r\n", rows) + "\r\n";
	}

	private static ExternalSettingsFile packageFile(String name) {
		return new ExternalSettingsFile(name, resource(name));
	}

	/**
	 * Get a configuration with different filter ids, like filters added on the
	 * canvas.
	 */
	private static SimConfig withIds(SimConfig config, Map<String, String> ids) {
		final List<FilterConfig> filters = new ArrayList<>();
		for ( FilterConfig f : config.filtersOrEmpty() ) {
			filters.add(new FilterConfig(ids.getOrDefault(f.id(), f.id()), f.factoryUid(),
					f.instanceKey(), f.settings(),
					f.filterIds() != null
							? f.filterIds().stream().map(id -> ids.getOrDefault(id, id)).toList()
							: null,
					f.comment(), f.notes()));
		}
		return new SimConfig(filters, ids.getOrDefault(config.mainChainId(), config.mainChainId()),
				config.datumQueueComment());
	}

	private static FilterConfig withComment(FilterConfig f, String comment) {
		return new FilterConfig(f.id(), f.factoryUid(), f.instanceKey(), f.settings(), f.filterIds(),
				comment, f.notes());
	}

	@Test
	public void importRecipe() {
		// WHEN
		SettingsImport result = service.importCsv(resource("daily-limit-tracking-settings.csv"));

		// THEN
		// @formatter:off
		final String param = StandardFilterTypes.PARAMETER + ".DayLimit";
		final String meter = StandardFilterTypes.VIRTUAL_METER + ".OverLimit";
		then(result.messages())
			.as("No problems")
			.isEmpty()
			;
		then(result.ignored())
			.as("Only filter settings")
			.isEmpty()
			;
		then(result.config().filtersOrEmpty())
			.as("Filters in file order")
			.extracting(FilterConfig::id, FilterConfig::factoryUid, FilterConfig::instanceKey)
			.containsExactly(
					tuple(param, StandardFilterTypes.PARAMETER, "DayLimit"),
					tuple(meter, StandardFilterTypes.VIRTUAL_METER, "OverLimit"),
					tuple(MAIN_ID, StandardFilterTypes.CHAIN, "Main"))
			;
		then(result.config().mainChainId())
			.as("Main chain from the Datum Queue setting")
			.isEqualTo(MAIN_ID)
			;
		then(filter(result.config(), MAIN_ID))
			.as("Chain members from transformUids, which are removed from the settings")
			.returns(List.of(param, meter), from(FilterConfig::filterIds))
			.returns(Map.of("uid", "Chain - Main"), from(FilterConfig::settings))
			;
		then(filter(result.config(), param).settings())
			.as("Settings in file order, with multi-line values kept as they are")
			.hasSize(9)
			.containsEntry("expressionConfigs[1].expression", """
					day_changed || !hasLocalState('wh-startofday')\r
					? saveLocalState('wh-startofday', wattHours)\r
					: localState('wh-startofday', wattHours)""")
			;
		// @formatter:on
	}

	@ParameterizedTest
	@ValueSource(strings = { "daily-limit-tracking-settings.csv", "main-filter-chain-settings.csv",
			"peak-tracking-settings.csv" })
	public void plainExportMatchesNodeExport(String name) {
		// GIVEN
		String csv = resource(name);
		SimConfig config = service.importCsv(csv).config();

		// WHEN
		SettingsExport result = export(config, Style.Plain);

		// THEN
		// @formatter:off
		then(result.csv())
			.as("Plain export is the SolarNode export, without modified dates")
			.isEqualTo(csv.replaceAll(",\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},", ",,"))
			;
		// @formatter:on
	}

	@ParameterizedTest
	@ValueSource(strings = { "daily-limit-tracking-settings.csv", "main-filter-chain-settings.csv",
			"peak-tracking-settings.csv", "commented-settings.csv" })
	public void organizedExportRoundTrip(String name) {
		// GIVEN
		SimConfig config = service.importCsv(resource(name)).config();
		String csv = export(config, Style.Organized).csv();

		// WHEN
		SimConfig result = service.importCsv(csv).config();

		// THEN
		// @formatter:off
		then(result.filtersOrEmpty())
			.as("Same filters and settings, in the order of the exported sections")
			.extracting(FilterConfig::id, FilterConfig::settings, FilterConfig::filterIds)
			.containsExactlyInAnyOrderElementsOf(config.filtersOrEmpty().stream()
					.map(f -> tuple(f.id(), f.settings(), f.filterIds())).toList())
			;
		then(result.mainChainId())
			.as("Same Main chain")
			.isEqualTo(config.mainChainId())
			;
		then(export(result, Style.Organized).csv())
			.as("Exporting again gives the same file")
			.isEqualTo(csv)
			;
		// @formatter:on
	}

	@Test
	public void importComments() {
		// WHEN
		SettingsImport result = service.importCsv(resource("commented-settings.csv"));

		// THEN
		// @formatter:off
		final String inverter = StandardFilterTypes.EXPRESSION + ".INV";
		then(result.messages())
			.as("No problems")
			.isEmpty()
			;
		then(result.config().datumQueueComment())
			.as("Datum Queue banner kept, without the blank rows around it")
			.isEqualTo("#############,,,,,\n# Datum Queue,,,,,\n#############,,,,,")
			;
		then(result.config().filtersOrEmpty())
			.as("Each banner kept with the filter after it")
			.extracting(FilterConfig::id, FilterConfig::comment)
			.containsExactly(
					tuple(MAIN_ID, "#####################,,,,,\n# Filter Chain - Main,,,,,\n#####################,,,,,"),
					tuple(inverter, "##################################,,,,,\n# Expression Filter - Inverter,,,,,\n##################################,,,,,"))
			;
		then(filter(result.config(), inverter).settings())
			.as("Quoted multi-line value kept")
			.hasEntrySatisfying("expressionConfigs[0].expression", v -> then(v)
					.startsWith("has('operatingStatus')\n? (\n")
					.endsWith(")\n: 0"))
			;
		// @formatter:on
	}

	@Test
	public void organizedExport() {
		// GIVEN
		SimConfig config = service.importCsv(resource("commented-settings.csv")).config();

		// WHEN
		SettingsExport result = export(config, Style.Organized);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No problems")
			.isEmpty()
			;
		then(result.csv())
			.as("Sections in execution order with their banners, and settings in form order")
			.isEqualTo(resource("commented-settings-organized.csv"))
			;
		// @formatter:on
	}

	@Test
	public void exportComment() {
		// GIVEN
		SimConfig config = new SimConfig(List
				.of(new FilterConfig("exp", StandardFilterTypes.EXPRESSION, "1", Map.of("uid", "EXP"),
						null, ",,,,,\n# First,,,,,\n,,,,,\n#,,,\nSecond, part\n,,,,,", null)),
				null);

		// WHEN
		SettingsExport result = export(config, Style.Organized);

		// THEN
		// @formatter:off
		then(result.csv())
			.as("Comment lines without empty columns, blank rows as empty lines, and text as a comment")
			.startsWith(HEADER + "\r\n# First\r\n\r\n#\r\n# Second, part\r\n\r\n"
					+ "net.solarnetwork.node.datum.samplefilter.expression.1,uid,EXP,,,\r\n")
			;
		// @formatter:on
	}

	@Test
	public void importMixed() {
		// WHEN
		SettingsImport result = service.importCsv(resource("mixed-settings.csv"));

		// THEN
		// @formatter:off
		final String throttle = StandardFilterTypes.THROTTLE + ".1";
		final String pvlib = StandardFilterTypes.PVLIB_POA + ".P1";
		final String bad = StandardFilterTypes.EXPRESSION + ".Bad:Name";
		final String unknown = FilterConfig.UNKNOWN_FACTORY_UID + ":Somewhere Else";
		then(result.config().filtersOrEmpty())
			.as("Filters, then the unknown filter")
			.extracting(FilterConfig::id, FilterConfig::factoryUid)
			.containsExactly(
					tuple(MAIN_ID, StandardFilterTypes.CHAIN),
					tuple(throttle, StandardFilterTypes.THROTTLE),
					tuple(pvlib, StandardFilterTypes.PVLIB_POA),
					tuple(bad, StandardFilterTypes.EXPRESSION),
					tuple(unknown, FilterConfig.UNKNOWN_FACTORY_UID))
			;
		then(filter(result.config(), MAIN_ID))
			.as("Chain keeps the filter that is not in the file, as an unknown filter")
			.returns(List.of(throttle, unknown, pvlib), from(FilterConfig::filterIds))
			.returns("#\n# Main chain\n#", from(FilterConfig::comment))
			;
		then(filter(result.config(), unknown).settings())
			.as("Unknown filter known by its Service Name")
			.isEqualTo(Map.of("uid", "Somewhere Else"))
			;
		then(filter(result.config(), throttle))
			.as("Numbered instance without a FACTORY row")
			.returns("1", from(FilterConfig::instanceKey))
			.returns(Map.of("uid", "Throttle", "frequencySeconds", "30"), from(FilterConfig::settings))
			.as("Note kept")
			.returns(Map.of("uid", "Limit everything"), from(FilterConfig::notes))
			.as("Commented-out setting kept with its own filter")
			.returns("# Throttle\n#net.solarnetwork.node.datum.samplefilter.limiter.1,sourceId,/meter/",
					from(FilterConfig::comment))
			;
		then(filter(result.config(), pvlib).settings())
			.as("Filter settings kept, including one the filter does not have")
			.isEqualTo(Map.of("uid", "POA", "parameters", "a,b"))
			;
		then(result.config())
			.as("Datum Queue comment and filter")
			.returns(MAIN_ID, from(SimConfig::mainChainId))
			.returns("# Settings from a test node", from(SimConfig::datumQueueComment))
			;
		then(result.messages())
			.as("Messages about skipped and changed settings")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(
					tuple(SimulationMessage.CODE_SETTING_SKIPPED, null),
					tuple(SimulationMessage.CODE_MISSING_FACTORY, throttle),
					tuple(SimulationMessage.CODE_SETTING_FLAGS, throttle),
					tuple(SimulationMessage.CODE_INVALID_INSTANCE_ID, bad),
					tuple(SimulationMessage.CODE_UNKNOWN_FILTER, unknown),
					tuple(SimulationMessage.CODE_GLOBAL_FILTER, null))
			;
		then(result.ignored())
			.as("Other components ignored")
			.extracting(IgnoredComponent::key, IgnoredComponent::instanceKey,
					IgnoredComponent::settingCount)
			.containsExactly(
					tuple("net.solarnetwork.node.runtime.dq", null, 1),
					tuple("solarnode.settings.lastBackupDate", null, 1),
					tuple("net.solarnetwork.node.datum.samplefilter.simple", "1", 2),
					tuple("net.solarnetwork.node.datum.modbus", "1", 2))
			;
		// @formatter:on
	}

	@Test
	public void exportMixed() {
		// GIVEN
		SimConfig imported = service.importCsv(resource("mixed-settings.csv")).config();
		SimConfig config = new SimConfig(imported.filtersOrEmpty().stream()
				.filter(f -> !"Bad:Name".equals(f.instanceKey())).toList(), imported.mainChainId(),
				imported.datumQueueComment());

		// WHEN
		SettingsExport result = export(config, Style.Organized);

		// THEN
		// @formatter:off
		then(result.csv())
			.as("Unknown filter used by the chain, without rows of its own")
			.contains("net.solarnetwork.node.datum.xform.user.Main,transformUids[1],Somewhere Else,,,\r\n")
			.doesNotContain("unknown")
			.as("Note in the note column")
			.contains("net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle,,,Limit everything\r\n")
			.as("FACTORY row added")
			.contains("net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,\r\n")
			.as("Setting the filter does not have kept as it is")
			.contains("net.solarnetwork.node.datum.filter.pvlib.poa.P1,parameters,\"a,b\",,,\r\n")
			.as("Comments kept, and ignored components left out")
			.contains("#net.solarnetwork.node.datum.samplefilter.limiter.1,sourceId,/meter/\r\n")
			.doesNotContain("modbus", "queueDelayMs", "solarnode.settings")
			;
		// @formatter:on
	}

	@Test
	public void importUnreadableRows() {
		// GIVEN
		String csv = HEADER + "a.b.1,x,1,two,,\r\n" + "a.b.1,y,2,,yesterday,\r\n";

		// THEN
		// @formatter:off
		thenThrownBy(() -> service.importCsv(csv))
			.as("SolarNode cannot import the file")
			.isInstanceOf(SimulationException.class)
			.extracting(e -> ((SimulationException) e).problems())
			.asInstanceOf(list(SimulationMessage.class))
			.extracting(SimulationMessage::level, SimulationMessage::code, SimulationMessage::message)
			.containsExactly(
					tuple(SimulationMessage.Level.Error, SimulationMessage.CODE_CSV_FORMAT,
							"Line 2 has the flags two, which is not a number."),
					tuple(SimulationMessage.Level.Error, SimulationMessage.CODE_CSV_FORMAT,
							"Line 3 has the modified date yesterday, which is not like 2025-10-01 12:34:56."))
			;
		// @formatter:on
	}

	@Test
	public void importWithoutHeader() {
		// GIVEN
		String csv = """
				net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1
				net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle
				""";

		// WHEN
		SettingsImport result = service.importCsv(csv);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Header warning, and the first row was skipped like SolarNode does")
			.extracting(SimulationMessage::code)
			.containsExactly(SimulationMessage.CODE_CSV_HEADER, SimulationMessage.CODE_MISSING_FACTORY)
			;
		// @formatter:on
	}

	@Test
	public void datumQueueFilterNotChain() {
		// GIVEN
		String csv = HEADER + """
				net.solarnetwork.node.runtime.dq,transformServiceUid,EXP
				net.solarnetwork.node.datum.samplefilter.expression.FACTORY,1,1
				net.solarnetwork.node.datum.samplefilter.expression.1,uid,EXP
				""";

		// WHEN
		SimConfig config = service.importCsv(csv).config();
		SettingsExport result = export(config, Style.Plain);

		// THEN
		// @formatter:off
		then(config.mainChainId())
			.as("Datum Queue filter is the expression filter")
			.isEqualTo(StandardFilterTypes.EXPRESSION + ".1")
			;
		then(result.csv())
			.as("Datum Queue filter exported")
			.endsWith("net.solarnetwork.node.runtime.dq,transformServiceUid,EXP,,,\r\n")
			;
		// @formatter:on
	}

	@Test
	public void exportInvalidInstanceIds() {
		// GIVEN
		SimConfig config = new SimConfig(
				List.of(new FilterConfig("a", StandardFilterTypes.EXPRESSION, "FACTORY", null, null),
						new FilterConfig("b", StandardFilterTypes.EXPRESSION, "a.b", null, null),
						new FilterConfig("c", StandardFilterTypes.THROTTLE, "1", null, null),
						new FilterConfig("d", StandardFilterTypes.THROTTLE, "1", null, null),
						new FilterConfig("e", StandardFilterTypes.EXPRESSION, "1", null, null)),
				null);

		// THEN
		// @formatter:off
		thenThrownBy(() -> export(config, Style.Organized))
			.as("Export blocked")
			.isInstanceOf(SimulationException.class)
			.extracting(e -> ((SimulationException) e).problems())
			.asInstanceOf(list(SimulationMessage.class))
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(
					tuple(SimulationMessage.CODE_INVALID_INSTANCE_ID, "a"),
					tuple(SimulationMessage.CODE_INVALID_INSTANCE_ID, "b"),
					tuple(SimulationMessage.CODE_DUPLICATE_INSTANCE_ID, "d"))
			;
		// @formatter:on
	}

	@Test
	public void exportAssignsInstanceIds() {
		// GIVEN
		SimConfig config = new SimConfig(List.of(
				new FilterConfig("main", StandardFilterTypes.CHAIN, null, Map.of("uid", "Chain - Main"),
						List.of("exp")),
				new FilterConfig("exp", StandardFilterTypes.EXPRESSION, null, Map.of("uid", "EXP"),
						null),
				new FilterConfig("exp2", StandardFilterTypes.EXPRESSION, "1", Map.of("uid", "EXP2"),
						null)),
				"main");

		// WHEN
		SettingsExport result = export(config, Style.Plain);

		// THEN
		// @formatter:off
		then(result.csv())
			.as("Main chain instance ID is Main, and the next number for the other")
			.contains("net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,\r\n")
			.contains("net.solarnetwork.node.datum.samplefilter.expression.FACTORY,2,2,,,\r\n")
			.contains("net.solarnetwork.node.datum.samplefilter.expression.FACTORY,1,1,,,\r\n")
			.as("Chain members from filterIds")
			.contains("net.solarnetwork.node.datum.xform.user.Main,transformUidsCount,1,,,\r\n")
			.contains("net.solarnetwork.node.datum.xform.user.Main,transformUids[0],EXP,,,\r\n")
			;
		then(result.messages())
			.as("Assigned instance IDs reported")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(
					tuple(SimulationMessage.CODE_INSTANCE_ID_ASSIGNED, "main"),
					tuple(SimulationMessage.CODE_INSTANCE_ID_ASSIGNED, "exp"))
			;
		// @formatter:on
	}

	@Test
	public void exportLeavesOutTransientSettings() {
		// GIVEN
		SimConfig config = new SimConfig(
				List.of(new FilterConfig("vm", StandardFilterTypes.VIRTUAL_METER, "1",
						Map.of("uid", "VM", "virtualMeterConfigsCount", "1",
								"virtualMeterConfigs[0].name", "wattHours",
								"virtualMeterConfigs[0].config", "1000"),
						null)),
				null);

		// WHEN
		SettingsExport result = export(config, Style.Organized);

		// THEN
		// @formatter:off
		then(result.csv())
			.as("Transient setting left out")
			.contains(",virtualMeterConfigs[0].name,wattHours,")
			.doesNotContain("virtualMeterConfigs[0].config")
			;
		then(result.messages())
			.as("Transient setting reported")
			.extracting(SimulationMessage::code)
			.containsExactly(SimulationMessage.CODE_TRANSIENT_SETTING)
			;
		// @formatter:on
	}

	@Test
	public void mergeReplacesAndAddsSettings() {
		// GIVEN
		final String throttle = StandardFilterTypes.THROTTLE + ".1";
		final SimConfig imported = withIds(
				service.importCsv(resource("peak-tracking-settings.csv")).config(),
				Map.of(StandardFilterTypes.PARAMETER + ".15Min", "filter-1",
						StandardFilterTypes.DOWNSAMPLE + ".Meters", "filter-2",
						StandardFilterTypes.EXPRESSION + ".Peak Power", "filter-3", MAIN_ID, "main"));
		final List<FilterConfig> filters = new ArrayList<>(imported.filtersOrEmpty());
		filters.replaceAll(f -> switch (f.id()) {
			case "filter-2" -> withComment(f, "# Meters");
			case "filter-3" -> withComment(f, "# Peak power");
			default -> f;
		});
		final SimConfig base = new SimConfig(filters, imported.mainChainId());

		// @formatter:off
		final String file = csv(
				"net.solarnetwork.node.datum.samplefilter.expression.Peak Power,sourceId,^/meter/2$,,,",
				"net.solarnetwork.node.datum.samplefilter.expression.FACTORY,Peak Power,Peak Power,,,",
				"# Meters, without a duration",
				"net.solarnetwork.node.datum.samplefilter.downsample.Meters,sampleDurationSecs,,,,",
				"net.solarnetwork.node.datum.samplefilter.downsample.FACTORY,Meters,Meters,,,",
				"net.solarnetwork.node.datum.filter.std.param.15Min,sourceId,/other/,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.1,frequencySeconds,10,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUidsCount,4,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[3],Throttle,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,");
		// @formatter:on

		// WHEN
		SettingsImport result = service.merge(new SettingsMergeRequest(base, file));

		// THEN
		// @formatter:off
		then(result.config().filtersOrEmpty())
			.as("Filters keep their ids and order, and the new filter follows them")
			.extracting(FilterConfig::id)
			.containsExactly("filter-1", "filter-2", "filter-3", "main", throttle)
			;
		then(result.config().mainChainId())
			.as("Same Main chain")
			.isEqualTo("main")
			;
		then(filter(result.config(), "main").filterIds())
			.as("The file's chain row adds a member, as on a node")
			.containsExactly("filter-1", "filter-3", "filter-2", throttle)
			;
		then(filter(result.config(), "filter-3"))
			.as("Setting replaced, and comment kept")
			.returns("^/meter/2$", from(f -> f.settings().get("sourceId")))
			.returns("# Peak power", from(FilterConfig::comment))
			;
		then(filter(result.config(), "filter-2"))
			.as("Empty value removes the setting, and the file's comment replaces the comment")
			.returns(false, from(f -> f.settings().containsKey("sampleDurationSecs")))
			.returns("# Meters, without a duration", from(FilterConfig::comment))
			;
		then(filter(result.config(), "filter-1").settings())
			.as("A row without its .FACTORY row is not imported, as on a node")
			.containsEntry("sourceId", "/meter/")
			;
		then(filter(result.config(), throttle))
			.as("New filter added")
			.returns("1", from(FilterConfig::instanceKey))
			.returns(Map.of("uid", "Throttle", "frequencySeconds", "10"), from(FilterConfig::settings))
			;
		then(result.messages())
			.as("Only the row without its .FACTORY row reported")
			.extracting(SimulationMessage::code)
			.containsExactly(SimulationMessage.CODE_MISSING_FACTORY)
			;
		then(result.ignored())
			.as("The row without its .FACTORY row ignored")
			.extracting(IgnoredComponent::key, IgnoredComponent::instanceKey,
					IgnoredComponent::settingCount)
			.containsExactly(tuple(StandardFilterTypes.PARAMETER + ".15Min", null, 1))
			;
		// @formatter:on
	}

	@Test
	public void mergeChainMembersByPosition() {
		// GIVEN
		final SimConfig base = service.importCsv(resource("peak-tracking-settings.csv")).config();
		final String throttle = StandardFilterTypes.THROTTLE + ".1";

		// @formatter:off
		final String file = csv(
				"net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[1],Throttle,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,");
		// @formatter:on

		// WHEN
		SettingsImport result = service.merge(new SettingsMergeRequest(base, file));

		// THEN
		// @formatter:off
		then(filter(result.config(), MAIN_ID).filterIds())
			.as("The file replaces only the second member, as on a node")
			.containsExactly(StandardFilterTypes.PARAMETER + ".15Min", throttle,
					StandardFilterTypes.DOWNSAMPLE + ".Meters")
			;
		then(filter(result.config(), StandardFilterTypes.EXPRESSION + ".Peak Power"))
			.as("The replaced member is still a filter")
			.isNotNull()
			;
		// @formatter:on
	}

	@Test
	public void mergeKeepsIdsAndUnknownFilters() {
		// GIVEN
		final String throttle = StandardFilterTypes.THROTTLE + ".1";
		final String elsewhere = FilterConfig.UNKNOWN_FACTORY_UID + ":Elsewhere";
		final String loose = FilterConfig.UNKNOWN_FACTORY_UID + ":Loose";
		final String second = StandardFilterTypes.CHAIN + ".Second";
		// @formatter:off
		final SimConfig base = new SimConfig(List.of(
				new FilterConfig("main", StandardFilterTypes.CHAIN, "Main",
						Map.of("uid", "Chain - Main"), List.of(throttle, elsewhere)),
				// an id from an import, but a different instance ID
				new FilterConfig(throttle, StandardFilterTypes.THROTTLE, "2",
						Map.of("uid", "Throttle"), null),
				new FilterConfig("x", StandardFilterTypes.EXPRESSION, null, Map.of("uid", "Exp"),
						null),
				new FilterConfig(elsewhere, FilterConfig.UNKNOWN_FACTORY_UID, null,
						Map.of("uid", "Elsewhere"), null),
				new FilterConfig(loose, FilterConfig.UNKNOWN_FACTORY_UID, null,
						Map.of("uid", "Loose"), null)),
				"main");
		final String file = csv(
				"net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Other Throttle,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,",
				"net.solarnetwork.node.datum.xform.user.Second,uid,Chain - Second,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Second,Second,,,",
				"net.solarnetwork.node.runtime.dq,transformServiceUid,Chain - Second,,,");
		// @formatter:on

		// WHEN
		SettingsImport result = service.merge(new SettingsMergeRequest(base, file));

		// THEN
		// @formatter:off
		then(result.config().filtersOrEmpty())
			.as("A new filter gets an id that is not in use, and unknown filters are kept")
			.extracting(FilterConfig::id, FilterConfig::instanceKey)
			.containsExactly(
					tuple("main", "Main"),
					tuple(throttle, "2"),
					tuple("x", null),
					tuple(throttle + "-2", "1"),
					tuple(second, "Second"),
					tuple(elsewhere, null),
					tuple(loose, null))
			;
		then(filter(result.config(), "main").filterIds())
			.as("Chain members kept")
			.containsExactly(throttle, elsewhere)
			;
		then(result.config().mainChainId())
			.as("The file's Datum Queue setting changes the Main chain")
			.isEqualTo(second)
			;
		then(result.messages())
			.as("No problems")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void externalPackages() {
		// GIVEN
		final ExternalSettingsRequest req = new ExternalSettingsRequest(
				List.of(packageFile(SETUP_LIVE_PACKAGE), packageFile(PING_PACKAGE),
						packageFile(DATUM_FILTERS_PACKAGE)));

		// WHEN
		ExternalSettings result = service.external(req);

		// THEN
		// @formatter:off
		then(result.files())
			.as("Files in name order, like SolarNode applies them")
			.extracting(ExternalSettingsSummary::name, ExternalSettingsSummary::settingCount,
					ExternalSettingsSummary::applied, ExternalSettingsSummary::filterIds)
			.containsExactly(
					tuple(DATUM_FILTERS_PACKAGE, 3, true, List.of(MAIN_ID)),
					tuple(PING_PACKAGE, 2, true, List.of()),
					tuple(SETUP_LIVE_PACKAGE, 6, true, List.of()))
			;
		then(result.files())
			.as("Components that are not filters ignored")
			.flatExtracting(ExternalSettingsSummary::ignored)
			.extracting(IgnoredComponent::key, IgnoredComponent::instanceKey,
					IgnoredComponent::settingCount)
			.containsExactly(
					tuple("net.solarnetwork.node.control.ping.http", "Default", 2),
					tuple("net.solarnetwork.node.datum.opmode.invoker", "SetupLive", 6))
			;
		then(result.files())
			.as("No problems with the files")
			.flatExtracting(ExternalSettingsSummary::messages)
			.isEmpty()
			;
		then(result.config().filtersOrEmpty())
			.as("The Main chain, without members")
			.extracting(FilterConfig::id, FilterConfig::settings, FilterConfig::filterIds)
			.containsExactly(tuple(MAIN_ID, Map.of("uid", "Chain - Main"), null))
			;
		then(result.config().mainChainId())
			.as("Main chain from the Datum Queue setting")
			.isEqualTo(MAIN_ID)
			;
		then(result.datumQueueFile())
			.as("Datum Queue setting's file")
			.isEqualTo(DATUM_FILTERS_PACKAGE)
			;
		then(result.origins())
			.as("Each setting's file")
			.containsExactly(Map.entry(MAIN_ID,
					new ExternalFilterOrigin(DATUM_FILTERS_PACKAGE, Map.of("uid", DATUM_FILTERS_PACKAGE))))
			;
		then(result.messages())
			.as("No problems")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void externalFirstFileWins() {
		// GIVEN
		final String throttle = StandardFilterTypes.THROTTLE + ".1";
		final String missing = FilterConfig.UNKNOWN_FACTORY_UID + ":Missing";
		// @formatter:off
		final ExternalSettingsFile b = new ExternalSettingsFile("b.csv", csv(
				"net.solarnetwork.node.datum.xform.user.Main,uid,Chain - B,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.1,frequencySeconds,10,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.1,sourceId,/b/,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,",
				"net.solarnetwork.node.runtime.dq,transformServiceUid,Chain - B,,,"));
		final ExternalSettingsFile a = new ExternalSettingsFile("A.csv", csv(
				"net.solarnetwork.node.datum.xform.user.Main,uid,Chain - A,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUidsCount,2,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[0],Throttle,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[1],Missing,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.1,frequencySeconds,5,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.1,sourceId,,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,",
				"net.solarnetwork.node.runtime.dq,transformServiceUid,Chain - A,,,"));
		// @formatter:on

		// WHEN
		ExternalSettings result = service.external(new ExternalSettingsRequest(List.of(b, a)));

		// THEN
		// @formatter:off
		then(result.files())
			.as("Files in name order, ignoring case")
			.extracting(ExternalSettingsSummary::name)
			.containsExactly("A.csv", "b.csv")
			;
		then(filter(result.config(), MAIN_ID))
			.as("The first file's settings win (the count is kept), and members resolve across files")
			.returns(Map.of("uid", "Chain - A", "transformUidsCount", "2"), from(FilterConfig::settings))
			.returns(List.of(throttle, missing), from(FilterConfig::filterIds))
			;
		then(filter(result.config(), throttle).settings())
			.as("Settings only in the later file are added, and empty values do nothing")
			.isEqualTo(Map.of("uid", "Throttle", "frequencySeconds", "5", "sourceId", "/b/"))
			;
		then(result.config().mainChainId())
			.as("Main chain from the first file's Datum Queue setting")
			.isEqualTo(MAIN_ID)
			;
		then(result.datumQueueFile())
			.as("Datum Queue setting's file")
			.isEqualTo("A.csv")
			;
		then(result.origins().get(throttle))
			.as("Each setting's file")
			.isEqualTo(new ExternalFilterOrigin("A.csv",
					Map.of("frequencySeconds", "A.csv", "uid", "b.csv", "sourceId", "b.csv")))
			;
		then(result.messages())
			.as("Member that is not in any file reported")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(tuple(SimulationMessage.CODE_UNKNOWN_FILTER, missing))
			;
		then(result.messages().getFirst().message())
			.as("Message says where the filter is not")
			.contains("not in the external settings")
			;
		// @formatter:on
	}

	/**
	 * Get a chain's members with {@code null} Unassigned slots.
	 */
	private static List<String> slots(int size, Map<Integer, String> members) {
		final List<String> result = new ArrayList<>(size);
		for ( int i = 0; i < size; i++ ) {
			result.add(members.get(i));
		}
		return result;
	}

	@Test
	public void externalChainMemberIndexes() {
		// GIVEN
		final String param = StandardFilterTypes.PARAMETER + ".Day";
		final String meter = StandardFilterTypes.VIRTUAL_METER + ".OverLimit";
		// @formatter:off
		final ExternalSettingsFile day = new ExternalSettingsFile("0300-day-period.csv", csv(
				"net.solarnetwork.node.datum.filter.std.param.Day,uid,PEX - Day Period,,,",
				"net.solarnetwork.node.datum.filter.std.param.FACTORY,Day,Day,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[9],PEX - Day Period,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,"));
		final ExternalSettingsFile limit = new ExternalSettingsFile("0400-day-limit.csv", csv(
				"net.solarnetwork.node.datum.samplefilter.virtmeter.OverLimit,uid,VM - Day Over Limit,,,",
				"net.solarnetwork.node.datum.samplefilter.virtmeter.FACTORY,OverLimit,OverLimit,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[19],VM - Day Over Limit,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,"));
		// @formatter:on

		// WHEN
		ExternalSettings result = service.external(
				new ExternalSettingsRequest(List.of(limit, packageFile(DATUM_FILTERS_PACKAGE), day)));

		// THEN
		// @formatter:off
		then(filter(result.config(), MAIN_ID).filterIds())
			.as("Each file adds its member at its index, after Unassigned slots")
			.isEqualTo(slots(20, Map.of(9, param, 19, meter)))
			;
		then(result.origins().get(MAIN_ID).settings())
			.as("The file of each member")
			.isEqualTo(Map.of("uid", DATUM_FILTERS_PACKAGE, "transformUids[9]", "0300-day-period.csv",
					"transformUids[19]", "0400-day-limit.csv"))
			;
		// @formatter:on
	}

	@Test
	public void importChainSlots() {
		// GIVEN
		final String throttle = StandardFilterTypes.THROTTLE + ".1";
		final String throttle2 = StandardFilterTypes.THROTTLE + ".2";
		// @formatter:off
		final String file = csv(
				"net.solarnetwork.node.datum.samplefilter.limiter.1,uid,One,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.2,uid,Two,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,2,2,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[0],One,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[2],Unassigned,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[3],Two,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,",
				"net.solarnetwork.node.datum.xform.user.Second,transformUidsCount,4,,,",
				"net.solarnetwork.node.datum.xform.user.Second,transformUids[1],Two,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Second,Second,,,");
		// @formatter:on

		// WHEN
		SettingsImport result = service.importCsv(file);

		// THEN
		final Map<Integer, String> mainMembers = new java.util.HashMap<>();
		mainMembers.put(0, throttle);
		mainMembers.put(3, throttle2);
		// @formatter:off
		then(filter(result.config(), MAIN_ID).filterIds())
			.as("Missing indexes and the Unassigned Service Name are Unassigned slots")
			.isEqualTo(slots(4, mainMembers))
			;
		then(filter(result.config(), StandardFilterTypes.CHAIN + ".Second").filterIds())
			.as("The count gives the chain's length")
			.isEqualTo(slots(4, Map.of(1, throttle2)))
			;
		then(result.config().filtersOrEmpty())
			.as("The Unassigned Service Name is not an unknown filter")
			.extracting(FilterConfig::factoryUid)
			.doesNotContain(FilterConfig.UNKNOWN_FACTORY_UID)
			;
		// @formatter:on
	}

	@Test
	public void exportChainSlots() {
		// GIVEN
		final List<String> members = new ArrayList<>(List.of("a", "x", "b"));
		members.set(1, null);
		// @formatter:off
		final SimConfig config = new SimConfig(List.of(
				new FilterConfig(MAIN_ID, StandardFilterTypes.CHAIN, "Main",
						Map.of("uid", "Chain - Main"), members),
				new FilterConfig("a", StandardFilterTypes.THROTTLE, "1", Map.of("uid", "A"), null),
				new FilterConfig("b", StandardFilterTypes.THROTTLE, "2", Map.of("uid", "B"), null)),
				MAIN_ID);
		// @formatter:on

		// WHEN
		SettingsExport result = export(config, Style.Plain);

		// THEN
		// @formatter:off
		then(result.csv())
			.as("An Unassigned slot has no row, and the count keeps the chain's length")
			.contains(
					"net.solarnetwork.node.datum.xform.user.Main,transformUidsCount,3,,,\r\n",
					"net.solarnetwork.node.datum.xform.user.Main,transformUids[0],A,,,\r\n",
					"net.solarnetwork.node.datum.xform.user.Main,transformUids[2],B,,,\r\n")
			.doesNotContain("transformUids[1]")
			;
		// @formatter:on
	}

	@Test
	public void exportExternalChainShifted() {
		// GIVEN
		final String param = StandardFilterTypes.PARAMETER + ".Day";
		final String meter = StandardFilterTypes.VIRTUAL_METER + ".OverLimit";
		// @formatter:off
		final ExternalSettingsFile day = new ExternalSettingsFile("0300-day-period.csv", csv(
				"net.solarnetwork.node.datum.filter.std.param.Day,uid,PEX - Day Period,,,",
				"net.solarnetwork.node.datum.filter.std.param.FACTORY,Day,Day,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[9],PEX - Day Period,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,"));
		final ExternalSettingsFile limit = new ExternalSettingsFile("0400-day-limit.csv", csv(
				"net.solarnetwork.node.datum.samplefilter.virtmeter.OverLimit,uid,VM - Day Over Limit,,,",
				"net.solarnetwork.node.datum.samplefilter.virtmeter.FACTORY,OverLimit,OverLimit,,,",
				"net.solarnetwork.node.datum.xform.user.Main,transformUids[19],VM - Day Over Limit,,,",
				"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,"));
		final ExternalSettings ext = service.external(new ExternalSettingsRequest(
				List.of(packageFile(DATUM_FILTERS_PACKAGE), day, limit)));

		// the first Unassigned slot deleted, so the members move down one
		final List<FilterConfig> filters = new ArrayList<>(ext.config().filtersOrEmpty());
		filters.replaceAll(f -> f.id().equals(MAIN_ID)
				? new FilterConfig(MAIN_ID, f.factoryUid(), f.instanceKey(), f.settings(),
						slots(19, Map.of(8, param, 18, meter)))
				: f);
		final SimConfig config = new SimConfig(filters, ext.config().mainChainId());
		// @formatter:on

		// WHEN
		SettingsExport result = service
				.exportCsv(new SettingsExportRequest(config, Style.Plain, ext.config()));

		// THEN
		// @formatter:off
		then(result.csv())
			.as("The moved members, Unassigned at the files' indexes, and no count")
			.isEqualTo(csv(
					"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,",
					"net.solarnetwork.node.datum.xform.user.Main,transformUids[18],VM - Day Over Limit,,,",
					"net.solarnetwork.node.datum.xform.user.Main,transformUids[19],Unassigned,,,",
					"net.solarnetwork.node.datum.xform.user.Main,transformUids[8],PEX - Day Period,,,",
					"net.solarnetwork.node.datum.xform.user.Main,transformUids[9],Unassigned,,,"))
			;
		// @formatter:on
	}

	@Test
	public void externalSkipsFileNodeCannotImport() {
		// GIVEN
		// @formatter:off
		final ExternalSettingsFile bad = new ExternalSettingsFile("bad.csv", csv(
				"net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Bad,x,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,"));
		// @formatter:on

		// WHEN
		ExternalSettings result = service
				.external(new ExternalSettingsRequest(List.of(bad, packageFile(DATUM_FILTERS_PACKAGE))));

		// THEN
		// @formatter:off
		then(result.files())
			.as("File with an invalid flags value not applied, like SolarNode")
			.extracting(ExternalSettingsSummary::name, ExternalSettingsSummary::applied)
			.containsExactly(tuple(DATUM_FILTERS_PACKAGE, true), tuple("bad.csv", false))
			;
		then(result.files().getLast().messages())
			.as("Error reported")
			.extracting(SimulationMessage::code)
			.containsExactly(SimulationMessage.CODE_CSV_FORMAT)
			;
		then(result.config().filtersOrEmpty())
			.as("Only the other file's filter")
			.extracting(FilterConfig::id)
			.containsExactly(MAIN_ID)
			;
		// @formatter:on
	}

	@Test
	public void exportLeavesOutExternalSettings() {
		// GIVEN
		final String extThrottle = StandardFilterTypes.THROTTLE + ".Ext";
		final String sameThrottle = StandardFilterTypes.THROTTLE + ".Same";
		final String throttle = StandardFilterTypes.THROTTLE + ".1";
		// @formatter:off
		final ExternalSettingsFile throttles = new ExternalSettingsFile("throttles.csv", csv(
				"net.solarnetwork.node.datum.samplefilter.limiter.Ext,uid,Ext Throttle,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.Ext,frequencySeconds,60,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,Ext,Ext,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.Same,uid,Same Throttle,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.Same,frequencySeconds,30,,,",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,Same,Same,,,"));
		final SimConfig external = service.external(new ExternalSettingsRequest(
				List.of(packageFile(DATUM_FILTERS_PACKAGE), throttles))).config();

		// the combined configuration: a filter added to the Main chain, and a changed setting
		final SimConfig config = new SimConfig(List.of(
				new FilterConfig(MAIN_ID, StandardFilterTypes.CHAIN, "Main",
						Map.of("uid", "Chain - Main"), List.of(extThrottle, throttle)),
				new FilterConfig(extThrottle, StandardFilterTypes.THROTTLE, "Ext",
						Map.of("uid", "Ext Throttle", "frequencySeconds", "120"), null),
				new FilterConfig(sameThrottle, StandardFilterTypes.THROTTLE, "Same",
						Map.of("uid", "Same Throttle", "frequencySeconds", "30"), null),
				new FilterConfig(throttle, StandardFilterTypes.THROTTLE, "1",
						Map.of("uid", "Throttle 1", "frequencySeconds", "10"), null)),
				MAIN_ID);
		// @formatter:on

		// WHEN
		SettingsExport result = service
				.exportCsv(new SettingsExportRequest(config, Style.Plain, external));

		// THEN
		// @formatter:off
		then(result.csv())
			.as("Only changed rows, with the .FACTORY row of each filter that has them, and no count over the package's chain")
			.isEqualTo(csv(
					"net.solarnetwork.node.datum.samplefilter.limiter.1,frequencySeconds,10,,,",
					"net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle 1,,,",
					"net.solarnetwork.node.datum.samplefilter.limiter.Ext,frequencySeconds,120,,,",
					"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,",
					"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,Ext,Ext,,,",
					"net.solarnetwork.node.datum.xform.user.FACTORY,Main,Main,,,",
					"net.solarnetwork.node.datum.xform.user.Main,transformUids[0],Ext Throttle,,,",
					"net.solarnetwork.node.datum.xform.user.Main,transformUids[1],Throttle 1,,,"))
			;
		then(result.messages())
			.as("No problems")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void exportOnlyExternalSettings() {
		// GIVEN
		final SimConfig external = service
				.external(new ExternalSettingsRequest(List.of(packageFile(DATUM_FILTERS_PACKAGE))))
				.config();

		// WHEN
		SettingsExport result = service
				.exportCsv(new SettingsExportRequest(external, Style.Organized, external));

		// THEN
		// @formatter:off
		then(result.csv())
			.as("Nothing to export but the header")
			.isEqualTo(HEADER)
			;
		// @formatter:on
	}

	@Test
	public void importControls() {
		// WHEN
		SettingsImport result = service.importCsv(resource("control-settings.csv"));

		// THEN
		// @formatter:off
		then(result.controls())
			.as("Mock controls publish on change, Modbus, BACnet, and GPIO Output ones on update, as the Control Datum Sources allow")
			.containsExactly(
					new NodeControlConfig("/mock/1", "Integer", "5", ControlPublishMode.Change, true),
					new NodeControlConfig("/mock/2", "Boolean", "false", ControlPublishMode.Change, true),
					new NodeControlConfig("/modbus/relay", "Boolean", null, ControlPublishMode.Update, true),
					new NodeControlConfig("/modbus/limit", "Percent", null, ControlPublishMode.Update, true, "limit", null),
					new NodeControlConfig("therm/setpoint/1", "Float", null, ControlPublishMode.Never, false),
					new NodeControlConfig("therm/away", "Boolean", null, ControlPublishMode.Never, false),
					new NodeControlConfig("/gpio/relay/1", "Boolean", "false", ControlPublishMode.Update, true, "relay", "Instantaneous"))
			;
		then(result.conductors())
			.as("The Control Conductor's settings")
			.containsExactly(new ControlConductorConfig(Map.of(
					"uid", "HVAC DR",
					"taskConfigsCount", "1",
					"taskConfigs[0].controlId", "/modbus/limit",
					"taskConfigs[0].executionOffset", "0",
					"taskConfigs[0].value", "50")))
			;
		then(result.config().filtersOrEmpty())
			.as("Only the filter is a filter, with the comment before it")
			.extracting(FilterConfig::id, FilterConfig::comment)
			.containsExactly(tuple("net.solarnetwork.node.datum.samplefilter.limiter.1", "# The throttle,,,,,"))
			;
		then(result.ignored())
			.as("The control components are not ignored")
			.isEmpty()
			;
		then(result.messages())
			.as("The BACnet control has no FACTORY row, and messages describe the controls")
			.extracting(SimulationMessage::code, SimulationMessage::message)
			.containsExactly(
					tuple(SimulationMessage.CODE_MISSING_FACTORY,
							"The file has no net.solarnetwork.node.control.bacnet.FACTORY row for the BACnet Control \"Therm\", so SolarNode would not create it. The simulator imported it anyway."),
					tuple(SimulationMessage.CODE_CONTROL_SETTINGS,
							"The GPIO Control \"Relays\" has the Input controls /gpio/switch/1, /gpio/switch/2, which are left out, because they change only with their hardware input, and SolarNode declines to set them."),
					tuple(SimulationMessage.CODE_CONTROL_SETTINGS,
							"No Control Datum Source publishes the change events of therm/setpoint/1, therm/away, so they never publish datum."))
			;
		// @formatter:on
	}

	@Test
	public void importControlsWithoutDatumSource() {
		// GIVEN
		String csv = csv("net.solarnetwork.node.control.mock.FACTORY,1,1",
				"net.solarnetwork.node.control.mock.1,controlId,/mock/1",
				"net.solarnetwork.node.control.mock.1,controlTypeValue,Float",
				"net.solarnetwork.node.control.modbus.FACTORY,1,1",
				"net.solarnetwork.node.control.modbus.1,propConfigs[0].controlId,/modbus/1",
				"net.solarnetwork.node.control.modbus.1,propConfigs[0].controlPropertyTypeKey,x",
				"net.solarnetwork.node.control.modbus.1,propConfigs[1].controlPropertyTypeKey,i");

		// WHEN
		SettingsImport result = service.importCsv(csv);

		// THEN
		// @formatter:off
		then(result.controls())
			.as("Controls publish like a default Control Datum Source; unknown types are Boolean")
			.containsExactly(
					new NodeControlConfig("/mock/1", "Float", "false", ControlPublishMode.Change, false),
					new NodeControlConfig("/modbus/1", "Boolean", null, ControlPublishMode.Update, false))
			;
		then(result.conductors())
			.as("No conductors")
			.isNull()
			;
		then(result.messages())
			.as("The control without a Control ID is left out")
			.extracting(SimulationMessage::code)
			.containsExactly(SimulationMessage.CODE_INVALID_CONTROL, SimulationMessage.CODE_CONTROL_SETTINGS)
			;
		// @formatter:on
	}

	@Test
	public void importModeSwitches() {
		// GIVEN
		String csv = csv("net.solarnetwork.node.control.opmode.switch.FACTORY,1,1",
				"net.solarnetwork.node.control.opmode.switch.1,controlId,/switch/boost",
				"net.solarnetwork.node.control.opmode.switch.1,mode,boost",
				"net.solarnetwork.node.control.opmode.switch.FACTORY,2,2",
				"net.solarnetwork.node.control.opmode.switch.2,controlId,/switch/other",
				"net.solarnetwork.node.datum.control.FACTORY,1,1",
				"net.solarnetwork.node.datum.control.1,controlIdRegexValue,^/switch/boost",
				"net.solarnetwork.node.datum.control.1,persistModeValue,PollAndEvent");

		// WHEN
		SettingsImport result = service.importCsv(csv);

		// THEN
		// @formatter:off
		then(result.controls())
			.as("Switches publish on change, as the Control Datum Sources allow")
			.containsExactly(
					NodeControlConfig.modeSwitch("/switch/boost", "boost", ControlPublishMode.Change, true),
					NodeControlConfig.modeSwitch("/switch/other", null, ControlPublishMode.Never, false))
			;
		then(result.ignored())
			.as("The switches are not ignored")
			.isEmpty()
			;
		then(result.messages())
			.as("No Control Datum Source publishes the second switch")
			.extracting(SimulationMessage::code, SimulationMessage::message)
			.containsExactly(tuple(SimulationMessage.CODE_CONTROL_SETTINGS,
					"No Control Datum Source publishes the change events of /switch/other, so it never publishes datum."))
			;
		// @formatter:on
	}

	@Test
	public void exportLeavesOutControls() {
		// GIVEN
		SettingsImport imported = service.importCsv(resource("control-settings.csv"));

		// WHEN
		SettingsExport result = export(imported.config(), Style.Plain);

		// THEN
		// @formatter:off
		then(result.csv())
			.as("Only the filter is exported")
			.contains("net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle")
			.doesNotContain("control")
			;
		// @formatter:on
	}

	@Test
	public void externalIgnoresControls() {
		// GIVEN
		ExternalSettingsFile file = new ExternalSettingsFile("controls.csv",
				resource("control-settings.csv"));

		// WHEN
		ExternalSettings result = service.external(new ExternalSettingsRequest(List.of(file)));

		// THEN
		// @formatter:off
		then(result.files())
			.as("One file")
			.hasSize(1)
			.first()
			.extracting(ExternalSettingsSummary::ignored, list(IgnoredComponent.class))
			.extracting(IgnoredComponent::key)
			.as("The control components are ignored")
			.contains(
					"net.solarnetwork.node.control.mock",
					"net.solarnetwork.node.control.modbus",
					"net.solarnetwork.node.control.gpiod",
					"net.solarnetwork.node.datum.control",
					"net.solarnetwork.node.control.conductor")
			;
		// @formatter:on
	}

	@Test
	public void importDatumSources() {
		// GIVEN
		String csv = csv("net.solarnetwork.node.datum.energymeter.mock.1,schedule,30000",
				"net.solarnetwork.node.datum.energymeter.mock.1,triggerCronExpression,0 * * * * ?",
				"net.solarnetwork.node.datum.energymeter.mock.1,jobService.datumDataSource.sourceId,/meter/1",
				"net.solarnetwork.node.datum.energymeter.mock.FACTORY,1,1",
				"net.solarnetwork.node.datum.fixed.FACTORY,Site,Site",
				"net.solarnetwork.node.datum.fixed.Site,triggerCronExpression,0 */5 * * * ?",
				"net.solarnetwork.node.datum.fixed.Site,jobDetail.jobDataMap['datumDataSource'].sourceId,/site",
				"net.solarnetwork.node.datum.fixed.Site,jobDetail.jobDataMap['datumDataSource'].uid,Site",
				"net.solarnetwork.node.datum.battery.mock.Bat,jobService.datumDataSource.sourceId,/bat",
				"net.solarnetwork.node.datum.modbus.FACTORY,1,1",
				"net.solarnetwork.node.datum.modbus.1,jobService.datumDataSource.sourceId,/modbus",
				"net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1",
				"net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle");

		// WHEN
		SettingsImport result = service.importCsv(csv);

		// THEN
		// @formatter:off
		then(result.datumSources())
			.as("The supported datum sources, in file order, with the 1.x keys changed to the current ones")
			.containsExactly(
					new DatumSourceConfig(null, "net.solarnetwork.node.datum.energymeter.mock", "1", Map.of(
							"schedule", "30000",
							"jobService.datumDataSource.sourceId", "/meter/1")),
					new DatumSourceConfig(null, "net.solarnetwork.node.datum.fixed", "Site", Map.of(
							"schedule", "0 */5 * * * ?",
							"jobService.datumDataSource.sourceId", "/site",
							"jobService.datumDataSource.uid", "Site")),
					new DatumSourceConfig(null, "net.solarnetwork.node.datum.battery.mock", "Bat", Map.of(
							"jobService.datumDataSource.sourceId", "/bat")))
			;
		then(result.config().filtersOrEmpty())
			.as("Only the filter is a filter")
			.extracting(FilterConfig::id)
			.containsExactly("net.solarnetwork.node.datum.samplefilter.limiter.1")
			;
		then(result.ignored())
			.as("The unsupported datum source is ignored")
			.extracting(IgnoredComponent::key, IgnoredComponent::instanceKey)
			.containsExactly(tuple("net.solarnetwork.node.datum.modbus", "1"))
			;
		then(result.messages())
			.as("The 1.x keys and the missing FACTORY row are described")
			.extracting(SimulationMessage::code, SimulationMessage::message)
			.containsExactly(
					tuple(SimulationMessage.CODE_LEGACY_SETTINGS,
							"The Mock Energy Meter \"1\" has SolarNode 1.x setting keys, which the simulator changed to the current ones."),
					tuple(SimulationMessage.CODE_LEGACY_SETTINGS,
							"The Fixed Data \"Site\" has SolarNode 1.x setting keys, which the simulator changed to the current ones."),
					tuple(SimulationMessage.CODE_MISSING_FACTORY,
							"The file has no net.solarnetwork.node.datum.battery.mock.FACTORY row for the Mock Battery \"Bat\", so SolarNode would not create it. The simulator imported it anyway."))
			;
		// @formatter:on
	}

	@Test
	public void externalIgnoresDatumSources() {
		// GIVEN
		ExternalSettingsFile file = new ExternalSettingsFile("sources.csv",
				csv("net.solarnetwork.node.datum.energymeter.mock.FACTORY,1,1",
						"net.solarnetwork.node.datum.energymeter.mock.1,schedule,30000"));

		// WHEN
		ExternalSettings result = service.external(new ExternalSettingsRequest(List.of(file)));

		// THEN
		// @formatter:off
		then(result.files())
			.as("One file")
			.hasSize(1)
			.first()
			.extracting(ExternalSettingsSummary::ignored, list(IgnoredComponent.class))
			.extracting(IgnoredComponent::key)
			.as("The datum source is ignored")
			.containsExactly("net.solarnetwork.node.datum.energymeter.mock")
			;
		// @formatter:on
	}

}
