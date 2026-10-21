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
import java.nio.file.Path;
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
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterBundleInfo;
import s10k.node.sim.filter.domain.FilterCapability;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.FilterStep.Status;
import s10k.node.sim.filter.domain.SettingField;
import s10k.node.sim.filter.domain.SettingsExport;
import s10k.node.sim.filter.domain.SettingsExportRequest;
import s10k.node.sim.filter.domain.SettingsForm;
import s10k.node.sim.filter.domain.SettingsFormRequest;
import s10k.node.sim.filter.domain.SettingsImport;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.csv.SettingsCsvService;
import s10k.node.sim.filter.form.SettingsFormService;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
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
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class CustomFilterLoaderTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SCALE = "com.example.solarnode.filter.scale";
	private static final String IDENTITY = "com.example.solarnode.filter.identity";
	private static final String BUNDLE = "com.example.solarnode.filter";

	private SpelExpressionService expressionService;
	private CustomFilterLoader loader;

	@BeforeEach
	public void setup() {
		expressionService = new SpelExpressionService();
		loader = new CustomFilterLoader(expressionService, StandardFilterTypes.definitions().stream()
				.map(FilterTypeDefinition::factoryUid).toList());
	}

	private static Path bundleJar() {
		final String path = System.getProperty("sim.test.filterBundle");
		then(path).as("The test filter bundle jar system property is set").isNotNull();
		return Path.of(path);
	}

	private CustomFilters load() {
		return loader.load(List.of(bundleJar().toString()));
	}

	private static FilterTypeRegistry registry(CustomFilters custom) {
		final List<FilterTypeDefinition> defs = new ArrayList<>(StandardFilterTypes.definitions());
		defs.addAll(custom.definitions());
		return new FilterTypeRegistry(defs, custom.unavailable());
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
					FilterBundle::blueprint)
			.containsExactly(tuple(BUNDLE, "1.0.0", List.of(SCALE), true))
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
			.hasSize(5)
			.satisfies(lines -> then(lines.get(0)).isEqualTo("Custom filters: 1 available from 1 jar"))
			.satisfies(lines -> then(lines.get(1)).contains(BUNDLE + " 1.0.0", "Scale Filter (" + SCALE + ")"))
			.satisfies(lines -> then(lines.get(2)).startsWith("    Warning: ").contains("com.example.missing"))
			.satisfies(lines -> then(lines.get(3)).startsWith("    Warning: ").contains("net.solarnetwork.service"))
			.satisfies(lines -> then(lines.get(4)).contains("Not available: Identity Datum Filter"))
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

}
