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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import s10k.node.sim.filter.csv.SettingsCsvService;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.IgnoredComponent;
import s10k.node.sim.filter.domain.SettingsExport;
import s10k.node.sim.filter.domain.SettingsExportRequest;
import s10k.node.sim.filter.domain.SettingsExportRequest.Style;
import s10k.node.sim.filter.domain.SettingsImport;
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
 * comment banners.
 * </p>
 *
 * @author matt
 * @version 1.0
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
		final String pvlib = "net.solarnetwork.node.datum.filter.pvlib.poa.P1";
		final String bad = StandardFilterTypes.EXPRESSION + ".Bad:Name";
		final String external = FilterConfig.EXTERNAL_FACTORY_UID + ":Somewhere Else";
		then(result.config().filtersOrEmpty())
			.as("Filters, including the unsupported filter, then the external filter")
			.extracting(FilterConfig::id, FilterConfig::factoryUid)
			.containsExactly(
					tuple(MAIN_ID, StandardFilterTypes.CHAIN),
					tuple(throttle, StandardFilterTypes.THROTTLE),
					tuple(pvlib, "net.solarnetwork.node.datum.filter.pvlib.poa"),
					tuple(bad, StandardFilterTypes.EXPRESSION),
					tuple(external, FilterConfig.EXTERNAL_FACTORY_UID))
			;
		then(filter(result.config(), MAIN_ID))
			.as("Chain keeps the filter that is not in the file, as an external filter")
			.returns(List.of(throttle, external, pvlib), from(FilterConfig::filterIds))
			.returns("#\n# Main chain\n#", from(FilterConfig::comment))
			;
		then(filter(result.config(), external).settings())
			.as("External filter known by its Service Name")
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
			.as("Unsupported filter settings kept")
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
					tuple(SimulationMessage.CODE_UNSUPPORTED_FILTER, pvlib),
					tuple(SimulationMessage.CODE_INVALID_INSTANCE_ID, bad),
					tuple(SimulationMessage.CODE_EXTERNAL_FILTER, external),
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
			.as("External filter used by the chain, without rows of its own")
			.contains("net.solarnetwork.node.datum.xform.user.Main,transformUids[1],Somewhere Else,,,\r\n")
			.doesNotContain("external")
			.as("Note in the note column")
			.contains("net.solarnetwork.node.datum.samplefilter.limiter.1,uid,Throttle,,,Limit everything\r\n")
			.as("FACTORY row added")
			.contains("net.solarnetwork.node.datum.samplefilter.limiter.FACTORY,1,1,,,\r\n")
			.as("Unsupported filter settings as they are")
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

}
