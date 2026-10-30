/* ==================================================================
 * CustomFilterLoaderTests.java - 21 Oct 2026 4:12:08 pm
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

package s10k.node.sim.filter.custom.test;

import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import java.math.BigDecimal;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.custom.CustomFilterLoader;
import s10k.node.sim.filter.custom.CustomFilters;
import s10k.node.sim.filter.custom.FilterBundle;
import s10k.node.sim.filter.custom.ImportCheck;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.DatumSourceConfig;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterBundleInfo;
import s10k.node.sim.filter.domain.FilterCapability;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.FilterStep.Status;
import s10k.node.sim.filter.domain.OpModeInvokerConfig;
import s10k.node.sim.filter.domain.SettingField;
import s10k.node.sim.filter.domain.SettingsExport;
import s10k.node.sim.filter.domain.SettingsExportRequest;
import s10k.node.sim.filter.domain.SettingsForm;
import s10k.node.sim.filter.domain.SettingsFormRequest;
import s10k.node.sim.filter.domain.SettingsImport;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.csv.SettingsCsvService;
import s10k.node.sim.filter.form.SettingsFormService;
import s10k.node.sim.filter.registry.DatumSourceTypeDefinition;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardDatumSourceTypes;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.registry.UnavailableFilterType;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import net.solarnetwork.node.datum.filter.std.PropertyDatumFilterService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for the {@link CustomFilterLoader} class, with the test filter
 * bundle that the build makes from the {@code testFilterBundle} source set.
 *
 * <p>
 * The bundle has a Scale filter, which uses a library jar the bundle embeds,
 * and an Identity filter, which needs a service the simulator does not provide.
 * It also has a Counter datum source, a Multi Counter multi datum source, and a
 * Modbus Counter datum source, which needs a service the simulator does not
 * provide.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public class CustomFilterLoaderTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SCALE = "com.example.solarnode.filter.scale";
	private static final String IDENTITY = "com.example.solarnode.filter.identity";
	private static final String BUNDLE = "com.example.solarnode.filter";
	private static final String COUNTER = "com.example.solarnode.datum.counter";
	private static final String MODBUS_COUNTER = "com.example.solarnode.datum.modbus";
	private static final String MULTI_COUNTER = "com.example.solarnode.datum.counter.multi";

	private SpelExpressionService expressionService;
	private CustomFilterLoader loader;

	@BeforeEach
	public void setup() {
		expressionService = new SpelExpressionService();
		loader = new CustomFilterLoader(expressionService,
				StandardFilterTypes.definitions().stream().map(FilterTypeDefinition::factoryUid)
						.toList(),
				StandardDatumSourceTypes.definitions().stream()
						.map(DatumSourceTypeDefinition::factoryUid).toList());
	}

	private static Path bundleJar() {
		final String path = System.getProperty("sim.test.filterBundle");
		then(path).as("The test filter bundle jar system property is set").isNotNull();
		return Path.of(path);
	}

	private CustomFilters load() {
		return loader.load(List.of(bundleJar().toString()));
	}

	private static Path projectDir() {
		final String path = System.getProperty("sim.test.filterProject");
		then(path).as("The test filter project system property is set").isNotNull();
		return Path.of(path);
	}

	private static FilterTypeRegistry registry(CustomFilters custom) {
		final List<FilterTypeDefinition> defs = new ArrayList<>(StandardFilterTypes.definitions());
		defs.addAll(custom.definitions());
		return new FilterTypeRegistry(defs, custom.unavailable());
	}

	private static DatumSourceTypeRegistry datumSourceRegistry(CustomFilters custom) {
		final List<DatumSourceTypeDefinition> defs = new ArrayList<>(
				StandardDatumSourceTypes.definitions());
		defs.addAll(custom.datumSources());
		return new DatumSourceTypeRegistry(defs);
	}

	private static List<FilterConfig> identityChain() {
		return List.of(
				new FilterConfig("main", StandardFilterTypes.CHAIN, null, Map.of("uid", "Chain - Main"),
						List.of("id")),
				new FilterConfig("id", IDENTITY, null, Map.of("uid", "ID"), null));
	}

	private static List<FilterConfig> scaleChain(String factor) {
		final Map<String, String> settings = new LinkedHashMap<>();
		settings.put("uid", "Scale");
		settings.put("propertyName", "watts");
		if ( factor != null ) {
			settings.put("factor", factor);
		}
		return List.of(new FilterConfig("main", StandardFilterTypes.CHAIN, null,
				Map.of("uid", "Chain - Main"), List.of("scale")),
				new FilterConfig("scale", SCALE, null, settings, null));
	}

	@Test
	public void load_bundle() {
		// WHEN
		CustomFilters result = load();

		// THEN
		// @formatter:off
		then(result.problems())
			.as("No problems")
			.isEmpty()
			;
		then(result.bundles())
			.as("The bundle is loaded, with its manifest name and version, without parameters")
			.extracting(FilterBundle::name, FilterBundle::version, FilterBundle::factoryUids,
					FilterBundle::datumSourceUids, FilterBundle::blueprint)
			.containsExactly(
					tuple(BUNDLE, "1.0.0", List.of(SCALE), List.of(COUNTER, MULTI_COUNTER), true))
			;
		then(result.definitions())
			.as("The Scale filter type is available")
			.hasSize(1)
			.element(0)
			.returns(SCALE, from(FilterTypeDefinition::factoryUid))
			.returns("Scale Datum Filter", from(FilterTypeDefinition::displayName))
			.as("Capability from the Datum Queue it uses")
			.returns(java.util.Set.of(FilterCapability.GeneratesDatum),
					from(FilterTypeDefinition::capabilities))
			;
		FilterTypeDefinition def = result.definitions().getFirst();
		then(def.info(Locale.ENGLISH).name())
			.as("The name comes from the bundle's message source title")
			.isEqualTo("Scale Filter")
			;
		then(def.origin())
			.as("The origin is the bundle")
			.isNotNull()
			.returns(BUNDLE, from(o -> o.bundleName()))
			.returns(bundleJar().toAbsolutePath().normalize(), from(o -> o.file()))
			;
		then(def.filterClass().getClassLoader())
			.as("The filter class comes from the bundle's class loader")
			.isSameAs(def.classLoader())
			;
		thenThrownBy(() -> Class.forName(def.filterClass().getName()))
			.as("The filter class is not on the simulator's class path")
			.isInstanceOf(ClassNotFoundException.class)
			;
		then(result.unavailable())
			.as("The Identity filter type is not available, because of its service")
			.singleElement()
			.returns(IDENTITY, from(UnavailableFilterType::factoryUid))
			.returns("Identity Datum Filter", from(UnavailableFilterType::displayName))
			.returns(new FilterBundleInfo(BUNDLE, "1.0.0"), from(UnavailableFilterType::bundle))
			.extracting(UnavailableFilterType::reason)
			.asString()
			.contains("net.solarnetwork.node.service.IdentityService")
			;
		// @formatter:on
	}

	@Test
	public void load_bundle_datumSources() {
		// WHEN
		CustomFilters result = load();

		// THEN
		// @formatter:off
		then(result.datumSources())
			.as("The Counter and Multi Counter datum source types are available")
			.extracting(DatumSourceTypeDefinition::factoryUid)
			.containsExactly(COUNTER, MULTI_COUNTER)
			;
		then(result.datumSources().getFirst())
			.as("The Counter datum source type")
			.returns(false, from(DatumSourceTypeDefinition::multi))
			.returns("Counter Datum Source", from(DatumSourceTypeDefinition::displayName))
			.as("The managed job's schedule")
			.returns("0/30 * * * * ?", from(DatumSourceTypeDefinition::defaultSchedule))
			.as("Published as a datum source")
			.returns(List.of(net.solarnetwork.node.service.DatumDataSource.class),
					from(DatumSourceTypeDefinition::services))
			;
		DatumSourceTypeDefinition def = result.datumSources().getFirst();
		then(def.info(Locale.ENGLISH))
			.as("The name comes from the bundle's message source title")
			.returns("Counter", from(i -> i.name()))
			.as("The bundle is in the information")
			.returns(new FilterBundleInfo(BUNDLE, "1.0.0"), from(i -> i.bundle()))
			;
		then(def.sourceClass().getClassLoader())
			.as("The datum source class comes from the bundle's class loader")
			.isSameAs(def.classLoader())
			;
		then(result.datumSources().get(1))
			.as("The Multi Counter datum source type is a multi datum source")
			.returns(true, from(DatumSourceTypeDefinition::multi))
			.returns("Multi Counter Datum Source", from(DatumSourceTypeDefinition::displayName))
			.as("The managed job's period schedule")
			.returns("20000", from(DatumSourceTypeDefinition::defaultSchedule))
			.as("Published as a multi datum source")
			.returns(List.of(net.solarnetwork.node.service.MultiDatumDataSource.class),
					from(DatumSourceTypeDefinition::services))
			.as("The multi datum source's settings")
			.returns(DatumSourceTypeDefinition.MULTI_SETTING_PREFIX,
					from(DatumSourceTypeDefinition::settingPrefix))
			.as("No sourceId property, so no Source ID check")
			.returns(null, from(DatumSourceTypeDefinition::sourceId))
			;
		then(result.unavailableDatumSources())
			.as("The Modbus Counter datum source type is not available, because of its service")
			.singleElement()
			.returns(MODBUS_COUNTER, from(UnavailableFilterType::factoryUid))
			.returns("Modbus Counter Datum Source", from(UnavailableFilterType::displayName))
			.returns(new FilterBundleInfo(BUNDLE, "1.0.0"), from(UnavailableFilterType::bundle))
			.extracting(UnavailableFilterType::reason)
			.asString()
			.contains("net.solarnetwork.node.io.modbus.ModbusNetwork")
			;
		// @formatter:on
	}

	@Test
	public void load_reservedDatumSourceUid() {
		// GIVEN
		loader = new CustomFilterLoader(expressionService, List.of(), List.of(COUNTER));

		// WHEN
		CustomFilters result = load();

		// THEN
		// @formatter:off
		then(result.datumSources())
			.as("A datum source type with a standard factory UID is not available")
			.extracting(DatumSourceTypeDefinition::factoryUid)
			.containsExactly(MULTI_COUNTER)
			;
		then(result.unavailableDatumSources())
			.extracting(UnavailableFilterType::factoryUid, UnavailableFilterType::reason)
			.contains(tuple(COUNTER, "It has the factory UID of a standard datum source type."))
			;
		// @formatter:on
	}

	@Test
	public void load_none() {
		// WHEN
		CustomFilters result = loader.load(List.of(" ", ""));

		// THEN
		// @formatter:off
		then(result)
			.as("No custom filters for a blank path")
			.isSameAs(CustomFilters.NONE)
			;
		then(result.describe(Locale.ENGLISH))
			.as("Nothing to describe")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void load_missingPath() {
		// WHEN
		CustomFilters result = loader.load(List.of("/no/such/path"));

		// THEN
		// @formatter:off
		then(result.definitions())
			.as("No filter types")
			.isEmpty()
			;
		then(result.problems())
			.as("The missing path is a problem")
			.singleElement()
			.asString()
			.contains("/no/such/path", "does not exist")
			;
		// @formatter:on
	}

	@Test
	public void load_directory() {
		// WHEN
		CustomFilters result = loader.load(List.of(bundleJar().getParent().toString()));

		// THEN
		// @formatter:off
		then(result.bundles())
			.as("The jar in the directory is loaded, not the one in its lib directory")
			.extracting(FilterBundle::name)
			.containsExactly(BUNDLE)
			;
		then(result.describe(Locale.ENGLISH))
			.as("The description lists the available types, the import warnings, and the unavailable types")
			.hasSize(6)
			.satisfies(lines -> then(lines.get(0)).isEqualTo("Custom filters: 1 filter and 2 datum sources available from 1 bundle"))
			.satisfies(lines -> then(lines.get(1)).contains(BUNDLE + " 1.0.0", "Scale Filter (" + SCALE + ")",
					"datum source Counter (" + COUNTER + ")",
					"datum source Multi Counter (" + MULTI_COUNTER + ")"))
			.satisfies(lines -> then(lines.get(2)).startsWith("    Warning: ").contains("com.example.missing"))
			.satisfies(lines -> then(lines.get(3)).startsWith("    Warning: ").contains("net.solarnetwork.service"))
			.satisfies(lines -> then(lines.get(4)).contains("Not available: Identity Datum Filter"))
			.satisfies(lines -> then(lines.get(5)).contains("Not available: datum source Modbus Counter Datum Source"))
			;
		// @formatter:on
	}

	@Test
	public void load_projectDirectory() throws Exception {
		// WHEN
		CustomFilters result = loader.load(List.of(projectDir().toString()));

		// THEN
		// @formatter:off
		then(result.problems())
			.as("No problems")
			.isEmpty()
			;
		then(result.bundles())
			.as("The bundle project is loaded, with its manifest name and version")
			.extracting(FilterBundle::name, FilterBundle::version, FilterBundle::file,
					FilterBundle::factoryUids)
			.containsExactly(tuple(BUNDLE + ".project", "1.1.0", projectDir(), List.of(SCALE)))
			;
		FilterTypeDefinition def = result.definitions().getFirst();
		then(def.info(Locale.ENGLISH).name())
			.as("The name comes from the message source in the source folder")
			.isEqualTo("Scale Filter")
			;
		then(def.filterClass().getProtectionDomain().getCodeSource().getLocation().toURI())
			.as("The class comes from the build.properties output folder")
			.isEqualTo(projectDir().resolve("build/eclipse").toUri())
			;
		// @formatter:on
	}

	@Test
	public void load_directoryOfProjects() {
		// WHEN
		CustomFilters result = loader.load(List.of(projectDir().getParent().toString()));

		// THEN
		// @formatter:off
		then(result.bundles())
			.as("The bundle project in the directory is loaded")
			.extracting(FilterBundle::name)
			.containsExactly(BUNDLE + ".project")
			;
		// @formatter:on
	}

	@Test
	public void load_copiesJars() throws Exception {
		// WHEN
		CustomFilters result = load();

		// THEN
		URLClassLoader cl = (URLClassLoader) result.definitions().getFirst().classLoader();
		// @formatter:off
		then(cl.getURLs())
			.as("The class loader loads copies of the jar and the jar it embeds, not the originals")
			.hasSize(2)
			.noneMatch(u -> u.toString().startsWith(bundleJar().getParent().toUri().toString()))
			;
		// @formatter:on
		result.close();
	}

	@Test
	public void projectDirectory_simulate() {
		// GIVEN
		SimulationEngine engine = new SimulationEngine(
				registry(loader.load(List.of(projectDir().toString()))), expressionService);
		SimulationRequest req = new SimulationRequest(new SimConfig(scaleChain("3"), "main"), null,
				List.of(JSON.readTree("""
						{"created":"2025-10-01 00:00:00Z","sourceId":"/meter/1","i":{"watts":100}}""")),
				null, null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(new BigDecimal(result.datum().getFirst().output().samples().i().get("watts").toString()))
			.as("Scaled with the bundle project's classes and embedded library")
			.isEqualByComparingTo("300")
			;
		// @formatter:on
	}

	@Test
	public void simulate() {
		// GIVEN
		SimulationEngine engine = new SimulationEngine(registry(load()), expressionService);
		SimulationRequest req = new SimulationRequest(new SimConfig(scaleChain("3"), "main"), null,
				List.of(JSON.readTree("""
						{"created":"2025-10-01 00:00:00Z","sourceId":"/meter/1","i":{"watts":100}}""")),
				null, null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		DatumTrace trace = result.datum().getFirst();
		then(trace.steps())
			.as("The custom filter changed the datum")
			.extracting(FilterStep::filterId, FilterStep::status)
			.containsExactly(tuple("main", Status.Changed), tuple("scale", Status.Changed))
			;
		then(new BigDecimal(trace.output().samples().i().get("watts").toString()))
			.as("Scaled by the factor setting, with the bundle's embedded library")
			.isEqualByComparingTo("300")
			;
		then(trace.output().samples().s())
			.as("Filter created with the simulated clock")
			.containsEntry("scaledAt", "2025-10-01T00:00:00Z")
			.as("Init method called")
			.containsEntry("started", true)
			.as("Run with the bundle's class loader")
			.containsEntry("contextLoader", true)
			.as("Blueprint service property set")
			.containsEntry("transientSettingDao", true)
			.as("Executor service runs tasks at once, on the simulation thread")
			.containsEntry("executedInline", true)
			;
		// @formatter:on
	}

	@Test
	public void simulate_blueprintDefault() {
		// GIVEN
		SimulationEngine engine = new SimulationEngine(registry(load()), expressionService);
		SimulationRequest req = new SimulationRequest(new SimConfig(scaleChain(null), "main"), null,
				List.of(JSON.readTree("""
						{"created":"2025-10-01 00:00:00Z","sourceId":"/meter/1","i":{"watts":100}}""")),
				null, null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(new BigDecimal(result.datum().getFirst().output().samples().i().get("watts").toString()))
			.as("Scaled by the factor the blueprint sets")
			.isEqualByComparingTo("200")
			;
		// @formatter:on
	}

	@Test
	public void simulate_unavailable() {
		// GIVEN
		SimulationEngine engine = new SimulationEngine(registry(load()), expressionService);
		SimulationRequest req = new SimulationRequest(new SimConfig(identityChain(), "main"), null,
				List.of(JSON.readTree("""
						{"created":"2025-10-01 00:00:00Z","sourceId":"/meter/1","i":{"watts":100}}""")),
				null, null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("The unavailable type is unsupported, so datum pass through it, with the reason")
			.singleElement()
			.returns(SimulationMessage.CODE_UNSUPPORTED_FILTER, from(SimulationMessage::code))
			.returns("id", from(SimulationMessage::filterId))
			.extracting(SimulationMessage::message)
			.asString()
			.contains("Identity Datum Filter", "not available",
					"net.solarnetwork.node.service.IdentityService")
			;
		// @formatter:on
	}

	@Test
	public void csvRoundTrip_unavailable() {
		// GIVEN
		FilterTypeRegistry registry = registry(load());
		SettingsCsvService csv = new SettingsCsvService(registry,
				new SettingsFormService(registry, expressionService));

		// WHEN
		SettingsExport export = csv.exportCsv(new SettingsExportRequest(
				new SimConfig(identityChain(), "main"), SettingsExportRequest.Style.Organized, null));
		SettingsImport imported = csv.importCsv(export.csv());

		// THEN
		// @formatter:off
		then(export.csv())
			.as("The export names the unavailable type in its banner")
			.contains("Identity Datum Filter: ID")
			;
		then(imported.config().filters())
			.as("The import keeps the unavailable filter, with its settings")
			.filteredOn(f -> IDENTITY.equals(f.factoryUid()))
			.singleElement()
			.extracting(FilterConfig::settings)
			.satisfies(s -> then(s).containsEntry("uid", "ID"))
			;
		then(imported.messages())
			.as("The import says the type is not available, and why")
			.filteredOn(m -> SimulationMessage.CODE_UNSUPPORTED_FILTER.equals(m.code()))
			.singleElement()
			.extracting(SimulationMessage::message)
			.asString()
			.contains("Identity Datum Filter", "net.solarnetwork.node.service.IdentityService")
			;
		// @formatter:on
	}

	@Test
	public void load_importWarnings() {
		// WHEN
		CustomFilters result = load();

		// THEN
		// @formatter:off
		then(result.bundles().getFirst().warnings())
			.as("Warnings for the missing package and the version the simulator does not have, "
					+ "but not for optional, JDK, ignored, or non-SolarNetwork version imports")
			.satisfiesExactly(
				w -> then(w).contains("com.example.missing").doesNotContain("com.example.optional"),
				w -> then(w).contains("net.solarnetwork.service [9.0,10.0)"))
			;
		// @formatter:on
	}

	@Test
	public void importCheck_standardBundle() throws Exception {
		// GIVEN
		ImportCheck check = new ImportCheck(getClass().getClassLoader());
		String importPackage;
		try (JarFile jar = new JarFile(Path.of(PropertyDatumFilterService.class.getProtectionDomain()
				.getCodeSource().getLocation().toURI()).toFile())) {
			importPackage = jar.getManifest().getMainAttributes().getValue("Import-Package");
		}

		// WHEN
		List<String> warnings = check.check(importPackage);

		// THEN
		// @formatter:off
		then(importPackage)
			.as("The standard bundle imports packages")
			.isNotBlank()
			;
		then(warnings)
			.as("No warnings for the imports of a bundle the simulator runs")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void settingsForm() {
		// GIVEN
		SettingsFormService forms = new SettingsFormService(registry(load()), expressionService);

		// WHEN
		SettingsForm form = forms.form(new SettingsFormRequest(SCALE, Map.of("propertyName", "watts")),
				Locale.ENGLISH);

		// THEN
		// @formatter:off
		then(form.problems())
			.as("No problems")
			.isEmpty()
			;
		then(form.fields())
			.as("The bundle's settings, labelled from its message source, with the setting's "
					+ "default value (not the blueprint's) when it is not given, like SolarNode")
			.filteredOn(f -> "propertyName".equals(f.key()) || "factor".equals(f.key()))
			.extracting(SettingField::key, SettingField::label, SettingField::value)
			.containsExactly(
					tuple("propertyName", "Property", "watts"),
					tuple("factor", "Factor", "1"))
			;
		// @formatter:on
	}

	@Test
	public void csvRoundTrip() {
		// GIVEN
		FilterTypeRegistry registry = registry(load());
		SettingsCsvService csv = new SettingsCsvService(registry,
				new SettingsFormService(registry, expressionService));

		// WHEN
		SettingsExport export = csv.exportCsv(new SettingsExportRequest(
				new SimConfig(scaleChain("3"), "main"), SettingsExportRequest.Style.Plain, null));
		SettingsImport imported = csv.importCsv(export.csv());

		// THEN
		// @formatter:off
		then(export.csv())
			.as("The export has the custom filter's factory")
			.contains(SCALE + ".FACTORY")
			;
		then(imported.config().filters())
			.as("The import has the custom filter, with its settings")
			.filteredOn(f -> SCALE.equals(f.factoryUid()))
			.singleElement()
			.extracting(FilterConfig::settings)
			.satisfies(s -> then(s)
					.containsEntry("uid", "Scale")
					.containsEntry("propertyName", "watts")
					.containsEntry("factor", "3"))
			;
		// @formatter:on
	}

	private static SimulationRequest counterRequest(Map<String, String> settings) {
		return datumSourceRequest(List.of(new DatumSourceConfig("counter", COUNTER, null, settings)),
				null, null);
	}

	private static SimulationRequest datumSourceRequest(List<DatumSourceConfig> sources,
			List<OpModeInvokerConfig> invokers, List<String> opModes) {
		return new SimulationRequest(
				new SimConfig(List.of(new FilterConfig("main", StandardFilterTypes.CHAIN, null,
						Map.of("uid", "Chain - Main"), List.of())), "main"),
				null, null, sources, invokers, null,
				(opModes != null
						? new SimulationEnvironment(opModes, null, null, null, null, null, null, null,
								null)
						: null),
				new SimulationOptions(null, null, null, null, Instant.parse("2025-10-01T00:00:00Z"),
						null, Duration.ofMinutes(1), null));
	}

	private static DatumSourceConfig multiCounter(String... settings) {
		final Map<String, String> s = new LinkedHashMap<>();
		s.put(DatumSourceTypeDefinition.MULTI_SETTING_PREFIX + "uid", "Multi");
		s.put(DatumSourceTypeDefinition.MULTI_SETTING_PREFIX + "sourceIds", "/count/a, /count/b");
		for ( int i = 0; i + 1 < settings.length; i += 2 ) {
			s.put(settings[i], settings[i + 1]);
		}
		return new DatumSourceConfig("multi", MULTI_COUNTER, null, s);
	}

	@Test
	public void simulate_datumSource() {
		// GIVEN
		CustomFilters custom = load();
		SimulationEngine engine = new SimulationEngine(registry(custom), datumSourceRegistry(custom),
				expressionService, List.of());
		SimulationRequest req = counterRequest(
				Map.of(DatumSourceTypeDefinition.SETTING_PREFIX + "sourceId", "/count/1"));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum())
			.as("Polled on the blueprint's schedule, counting by the blueprint's step")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp(),
					t -> new BigDecimal(t.input().samples().i().get("count").toString()).intValue())
			.containsExactly(
					tuple("/count/1", Instant.parse("2025-10-01T00:00:30Z"), 2),
					tuple("/count/1", Instant.parse("2025-10-01T00:01:00Z"), 4))
			;
		then(result.datum().getFirst().input().samples().s())
			.as("Init method called")
			.containsEntry("started", "true")
			.as("Polled with the bundle's class loader")
			.containsEntry("contextLoader", "true")
			;
		then(result.stats().polled())
			.as("Polled datum")
			.isEqualTo(2)
			;
		// @formatter:on
	}

	@Test
	public void simulate_datumSource_settings() {
		// GIVEN
		CustomFilters custom = load();
		SimulationEngine engine = new SimulationEngine(registry(custom), datumSourceRegistry(custom),
				expressionService, List.of());
		SimulationRequest req = counterRequest(
				Map.of(DatumSourceTypeDefinition.SETTING_PREFIX + "sourceId", "/count/1",
						DatumSourceTypeDefinition.SETTING_PREFIX + "step", "5", "schedule", "60000"));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Polled at the start and each minute, counting by the step setting")
			.extracting(t -> t.input().timestamp(),
					t -> new BigDecimal(t.input().samples().i().get("count").toString()).intValue())
			.containsExactly(
					tuple(Instant.parse("2025-10-01T00:00:00Z"), 5),
					tuple(Instant.parse("2025-10-01T00:01:00Z"), 10))
			;
		// @formatter:on
	}

	@Test
	public void simulate_datumSource_noSourceId() {
		// GIVEN
		CustomFilters custom = load();
		SimulationEngine engine = new SimulationEngine(registry(custom), datumSourceRegistry(custom),
				expressionService, List.of());

		// WHEN
		SimulationResult result = engine.simulate(counterRequest(Map.of()));

		// THEN
		// @formatter:off
		then(result.messages())
			.as("The Source ID check uses the custom class's sourceId property")
			.extracting(SimulationMessage::code)
			.contains(SimulationMessage.CODE_INVALID_DATUM_SOURCE)
			;
		// @formatter:on
	}

	@Test
	public void settingsForm_datumSource() {
		// GIVEN
		CustomFilters custom = load();
		SettingsFormService forms = new SettingsFormService(registry(custom),
				datumSourceRegistry(custom), expressionService);

		// WHEN
		SettingsForm form = forms.form(
				new SettingsFormRequest(COUNTER,
						Map.of(DatumSourceTypeDefinition.SETTING_PREFIX + "sourceId", "/count/1")),
				Locale.ENGLISH);

		// THEN
		// @formatter:off
		then(form.problems())
			.as("No problems")
			.isEmpty()
			;
		then(form.fields())
			.as("The managed job's schedule, with the blueprint's default, then the datum source's "
					+ "settings, labelled from its message source")
			.filteredOn(f -> f.key() != null && (f.key().equals("schedule")
					|| f.key().endsWith("sourceId") || f.key().endsWith("step")))
			.extracting(SettingField::key, SettingField::label, SettingField::value)
			.containsExactly(
					tuple("schedule", "Schedule", "0/30 * * * * ?"),
					tuple(DatumSourceTypeDefinition.SETTING_PREFIX + "sourceId", "Source ID", "/count/1"),
					tuple(DatumSourceTypeDefinition.SETTING_PREFIX + "step", "Step", "1"))
			;
		// @formatter:on
	}

	@Test
	public void simulate_multiDatumSource() {
		// GIVEN
		CustomFilters custom = load();
		SimulationEngine engine = new SimulationEngine(registry(custom), datumSourceRegistry(custom),
				expressionService, List.of());
		SimulationRequest req = datumSourceRequest(
				List.of(multiCounter(DatumSourceTypeDefinition.MULTI_SETTING_PREFIX + "step", "3")),
				null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum())
			.as("Each poll on the blueprint's period gives a datum per source ID, counting by the "
					+ "step setting")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp(),
					t -> new BigDecimal(t.input().samples().i().get("count").toString()).intValue())
			.containsExactly(
					tuple("/count/a", Instant.parse("2025-10-01T00:00:00Z"), 3),
					tuple("/count/b", Instant.parse("2025-10-01T00:00:00Z"), 3),
					tuple("/count/a", Instant.parse("2025-10-01T00:00:20Z"), 6),
					tuple("/count/b", Instant.parse("2025-10-01T00:00:20Z"), 6),
					tuple("/count/a", Instant.parse("2025-10-01T00:00:40Z"), 9),
					tuple("/count/b", Instant.parse("2025-10-01T00:00:40Z"), 9),
					tuple("/count/a", Instant.parse("2025-10-01T00:01:00Z"), 12),
					tuple("/count/b", Instant.parse("2025-10-01T00:01:00Z"), 12))
			;
		then(result.datum())
			.as("Polled datum of the multi datum source")
			.extracting(DatumTrace::origin)
			.allSatisfy(o -> then(o)
					.returns(DatumOrigin.Type.Polled, from(DatumOrigin::type))
					.returns("multi", from(DatumOrigin::datumSourceId)))
			;
		then(result.stats().polled())
			.as("Polled datum")
			.isEqualTo(8)
			;
		// @formatter:on
	}

	@Test
	public void simulate_multiDatumSource_invoker() {
		// GIVEN
		CustomFilters custom = load();
		SimulationEngine engine = new SimulationEngine(registry(custom), datumSourceRegistry(custom),
				expressionService, List.of());
		Map<String, String> invoker = new LinkedHashMap<>();
		invoker.put("uid", "Invoker");
		invoker.put("operationalMode", "boost");
		invoker.put("configurationsCount", "1");
		invoker.put("configurations[0].uid", "Multi");
		invoker.put("configurations[0].schedule", "30");
		SimulationRequest req = datumSourceRequest(List.of(multiCounter("schedule", "0 0 0 1 1 ?")),
				List.of(new OpModeInvokerConfig("inv", null, invoker)), List.of("boost"));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("The invoker's configuration matches the multi datum source, which it polls")
			.extracting(SimulationMessage::code)
			.doesNotContain(SimulationMessage.CODE_INVALID_INVOKER)
			;
		then(result.datum())
			.as("The invoker polls the multi datum source, a datum per source ID")
			.isNotEmpty()
			.allSatisfy(t -> then(t.origin())
					.returns(DatumOrigin.Type.Polled, from(DatumOrigin::type))
					.returns("multi", from(DatumOrigin::datumSourceId))
					.returns("inv", from(DatumOrigin::invokerId)))
			.extracting(t -> t.input().sourceId())
			.contains("/count/a", "/count/b")
			;
		// @formatter:on
	}

	@Test
	public void settingsForm_multiDatumSource() {
		// GIVEN
		CustomFilters custom = load();
		SettingsFormService forms = new SettingsFormService(registry(custom),
				datumSourceRegistry(custom), expressionService);

		// WHEN
		SettingsForm form = forms.form(
				new SettingsFormRequest(MULTI_COUNTER, Map
						.of(DatumSourceTypeDefinition.MULTI_SETTING_PREFIX + "sourceIds", "/count/a")),
				Locale.ENGLISH);

		// THEN
		// @formatter:off
		then(form.problems())
			.as("No problems")
			.isEmpty()
			;
		then(form.fields())
			.as("The managed job's schedule, with the blueprint's default, then the multi datum "
					+ "source's settings, labelled from its message source")
			.filteredOn(f -> f.key() != null && (f.key().equals("schedule")
					|| f.key().endsWith("sourceIds") || f.key().endsWith("step")))
			.extracting(SettingField::key, SettingField::label, SettingField::value)
			.containsExactly(
					tuple("schedule", "Schedule", "20000"),
					tuple(DatumSourceTypeDefinition.MULTI_SETTING_PREFIX + "sourceIds", "Source IDs",
							"/count/a"),
					tuple(DatumSourceTypeDefinition.MULTI_SETTING_PREFIX + "step", "Step", "1"))
			;
		// @formatter:on
	}

	@Test
	public void csvImport_datumSource() {
		// GIVEN
		CustomFilters custom = load();
		FilterTypeRegistry registry = registry(custom);
		DatumSourceTypeRegistry sources = datumSourceRegistry(custom);
		SettingsCsvService csv = new SettingsCsvService(registry, sources,
				new SettingsFormService(registry, sources, expressionService));
		String file = """
				key,type,value,flags,modified,note
				%1$s.FACTORY,1,1,,,
				%1$s.1,jobService.datumDataSource.sourceId,/count/1,,,
				%1$s.1,schedule,60000,,,
				""".formatted(COUNTER);

		// WHEN
		SettingsImport imported = csv.importCsv(file);

		// THEN
		// @formatter:off
		then(imported.datumSources())
			.as("The custom datum source is imported as a datum source")
			.singleElement()
			.returns(COUNTER, from(DatumSourceConfig::factoryUid))
			.returns("1", from(DatumSourceConfig::instanceKey))
			.extracting(DatumSourceConfig::settings)
			.satisfies(s -> then(s)
					.containsEntry("jobService.datumDataSource.sourceId", "/count/1")
					.containsEntry("schedule", "60000"))
			;
		// @formatter:on
	}

}
