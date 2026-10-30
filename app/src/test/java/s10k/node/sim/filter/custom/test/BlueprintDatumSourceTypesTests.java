/* ==================================================================
 * BlueprintDatumSourceTypesTests.java - 30 Oct 2026 5:12:08 pm
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

import static org.assertj.core.api.BDDAssertions.then;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import s10k.node.sim.filter.blueprint.Blueprint;
import s10k.node.sim.filter.blueprint.BlueprintReader;
import s10k.node.sim.filter.blueprint.DatumSourceTypeDescriptor;
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter;
import s10k.node.sim.filter.custom.BlueprintDatumSourceTypes;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.registry.DatumSourceTypeDefinition;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardDatumSourceTypes;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import net.solarnetwork.node.datum.battery.mock.MockBatteryDatumDataSource;
import net.solarnetwork.node.datum.energymeter.mock.MockEnergyMeterDatumSource;
import net.solarnetwork.node.datum.fixed.FixedDatumDataSource;
import net.solarnetwork.node.datum.tou.TouDatumDataSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for the {@link BlueprintDatumSourceTypes} class, with the
 * blueprints of the standard datum source bundles.
 *
 * <p>
 * Defining the standard datum source types from their own blueprints, the way
 * custom datum source types are defined, checks the blueprint interpretation
 * and the simulated services that blueprints can refer to against real datum
 * sources. Their simulations should match the ones with the hand-written
 * {@link StandardDatumSourceTypes}, apart from the messages of their checks and
 * limitations, which blueprints do not have.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class BlueprintDatumSourceTypesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** A class in each datum source bundle. */
	private static final List<Class<?>> BUNDLE_CLASSES = List.of(FixedDatumDataSource.class,
			MockBatteryDatumDataSource.class, MockEnergyMeterDatumSource.class,
			TouDatumDataSource.class);

	/** The message codes of the hand-written checks and limitations. */
	private static final Set<String> HAND_WRITTEN_CODES = Set.of(SimulationMessage.CODE_FILTER_PROBLEM,
			SimulationMessage.CODE_FILTER_LIMITATION);

	private static List<BlueprintDatumSourceTypes.Result> results;

	@BeforeAll
	public static void setup() throws IOException, URISyntaxException {
		final BlueprintReader reader = new BlueprintReader();
		final SpelExpressionService expressionService = new SpelExpressionService();
		results = new ArrayList<>(4);
		for ( Class<?> bundleClass : BUNDLE_CLASSES ) {
			for ( DatumSourceTypeDescriptor d : FilterBlueprintInterpreter
					.interpret(readBundle(reader, bundleClass)).datumSourceTypes() ) {
				results.add(BlueprintDatumSourceTypes.define(d,
						BlueprintDatumSourceTypesTests.class.getClassLoader(), null, expressionService));
			}
		}
	}

	/**
	 * Read and merge all the blueprint XML files of the bundle that contains a
	 * class.
	 */
	private static Blueprint readBundle(BlueprintReader reader, Class<?> bundleClass)
			throws IOException, URISyntaxException {
		final Path location = Path
				.of(bundleClass.getProtectionDomain().getCodeSource().getLocation().toURI());
		try (FileSystem jarFs = (Files.isDirectory(location) ? null
				: FileSystems.newFileSystem(location))) {
			final Path root = (jarFs != null ? jarFs.getPath("/") : location);
			final List<Blueprint> blueprints = new ArrayList<>(1);
			try (Stream<Path> files = Files.list(root.resolve("OSGI-INF/blueprint"))) {
				for ( Path file : files.filter(p -> p.toString().endsWith(".xml")).sorted().toList() ) {
					try (InputStream in = Files.newInputStream(file)) {
						blueprints.add(reader.read(in));
					}
				}
			}
			return Blueprint.merge(blueprints);
		}
	}

	private static Map<String, DatumSourceTypeDefinition> definitions() {
		final Map<String, DatumSourceTypeDefinition> defs = new LinkedHashMap<>();
		for ( BlueprintDatumSourceTypes.Result r : results ) {
			if ( r instanceof BlueprintDatumSourceTypes.Defined(DatumSourceTypeDefinition def, var _) ) {
				defs.put(def.factoryUid(), def);
			}
		}
		return defs;
	}

	@Test
	public void defineStandard() {
		// THEN
		// @formatter:off
		then(results)
			.as("Every standard datum source type is available from its blueprint")
			.allSatisfy(r -> then(r).isInstanceOf(BlueprintDatumSourceTypes.Defined.class))
			.extracting(BlueprintDatumSourceTypes.Result::factoryUid)
			.as("Same datum source types as the hand-written ones")
			.containsExactlyInAnyOrderElementsOf(StandardDatumSourceTypes.definitions().stream()
					.map(DatumSourceTypeDefinition::factoryUid).toList())
			;
		for ( DatumSourceTypeDefinition standard : StandardDatumSourceTypes.definitions() ) {
			final DatumSourceTypeDefinition def = definitions().get(standard.factoryUid());
			then(def)
				.as("%s defined", standard.factoryUid())
				.isNotNull()
				.returns(standard.displayName(), DatumSourceTypeDefinition::displayName)
				.returns(standard.sourceClass(), DatumSourceTypeDefinition::sourceClass)
				.returns(standard.defaultSchedule(), DatumSourceTypeDefinition::defaultSchedule)
				;
			then(def.services())
				.as("%s published as the same services", standard.factoryUid())
				.containsExactlyInAnyOrderElementsOf(standard.services())
				;
		}
		// @formatter:on
	}

	@ParameterizedTest
	@ValueSource(strings = { "datum-sources", "opmode-invoker" })
	public void simulateStandard(String fixture) throws IOException {
		// GIVEN
		final SpelExpressionService expressionService = new SpelExpressionService();
		final FilterTypeRegistry filters = new FilterTypeRegistry(StandardFilterTypes.definitions());
		final SimulationEngine blueprintEngine = new SimulationEngine(filters,
				new DatumSourceTypeRegistry(definitions().values()), expressionService, List.of());
		final SimulationEngine standardEngine = new SimulationEngine(filters,
				DatumSourceTypeRegistry.standard(), expressionService, List.of());
		final SimulationRequest req;
		try (InputStream in = getClass()
				.getResourceAsStream("/s10k/node/sim/filter/smoke/" + fixture + ".json")) {
			req = JSON.readValue(in, SimulationRequest.class);
		}

		// WHEN
		SimulationResult blueprint = blueprintEngine.simulate(req);
		SimulationResult standard = standardEngine.simulate(req);

		// THEN
		// @formatter:off
		then(summary(blueprint))
			.as("Same simulation with the blueprint datum source types as with the standard ones")
			.isEqualTo(summary(standard))
			;
		// @formatter:on
	}

	private static Map<String, Object> summary(SimulationResult result) {
		final Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("messages",
				result.messages().stream().filter(m -> !HAND_WRITTEN_CODES.contains(m.code()))
						.map(SimulationMessage::code).toList());
		summary.put("stats", List.of(result.stats().processed(), result.stats().generated(),
				result.stats().polled(), result.stats().output(), result.stats().discarded()));
		final List<Object> datum = new ArrayList<>();
		for ( DatumTrace t : result.datum() ) {
			datum.add(List.of(t.seq(), String.valueOf(t.input().sourceId()),
					String.valueOf(t.input().samples()),
					t.steps().stream().map(s -> s.filterId() + ":" + s.status()).toList(),
					String.valueOf(t.output() != null ? t.output().samples() : null),
					String.valueOf(t.error())));
		}
		summary.put("datum", datum);
		summary.put("controls", String.valueOf(result.finalState().controls()));
		return summary;
	}

}
