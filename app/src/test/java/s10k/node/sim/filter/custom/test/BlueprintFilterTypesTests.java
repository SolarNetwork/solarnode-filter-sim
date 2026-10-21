/* ==================================================================
 * BlueprintFilterTypesTests.java - 21 Oct 2026 4:12:08 pm
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
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter;
import s10k.node.sim.filter.blueprint.FilterTypeDescriptor;
import s10k.node.sim.filter.custom.BlueprintFilterTypes;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import net.solarnetwork.node.datum.filter.std.PropertyDatumFilterService;
import net.solarnetwork.node.datum.filter.tariff.TariffDatumFilterService;
import net.solarnetwork.node.datum.pvlib.PvlibPoaDatumFilterService;
import net.solarnetwork.node.metrics.harvester.MetricHarvesterDatumFilterService;
import net.solarnetwork.node.service.support.DatumFilterChainService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for the {@link BlueprintFilterTypes} class, with the blueprints of
 * the standard filter bundles.
 *
 * <p>
 * Defining the standard filter types from their own blueprints, the way custom
 * filter types are defined, checks the simulated services that blueprints can
 * refer to against real filters. Their simulations should match the ones with
 * the hand-written {@link StandardFilterTypes}, apart from the messages of
 * their checks and limitations, which blueprints do not have.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class BlueprintFilterTypesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/** A class in each bundle with user datum filters. */
	private static final List<Class<?>> BUNDLE_CLASSES = List.of(DatumFilterChainService.class,
			PropertyDatumFilterService.class, TariffDatumFilterService.class,
			PvlibPoaDatumFilterService.class, MetricHarvesterDatumFilterService.class);

	/** The message codes of the hand-written checks and limitations. */
	private static final Set<String> HAND_WRITTEN_CODES = Set.of(SimulationMessage.CODE_FILTER_PROBLEM,
			SimulationMessage.CODE_FILTER_LIMITATION);

	/**
	 * The filter of the {@code standard-filters} fixture whose blueprint wiring
	 * differs from the hand-written definition: the blueprint sets the Join
	 * filter's {@code startupDelay} to 300 seconds, so its startup task is
	 * still pending when the fixture ends. The hand-written definition starts
	 * it at once, like a node does when the filter's settings are saved.
	 */
	private static final String JOIN_FILTER_ID = "join";

	private static List<BlueprintFilterTypes.Result> results;

	@BeforeAll
	public static void setup() throws IOException, URISyntaxException {
		final BlueprintReader reader = new BlueprintReader();
		final SpelExpressionService expressionService = new SpelExpressionService();
		results = new ArrayList<>(20);
		for ( Class<?> bundleClass : BUNDLE_CLASSES ) {
			for ( FilterTypeDescriptor d : FilterBlueprintInterpreter
					.interpret(readBundle(reader, bundleClass)).filterTypes() ) {
				results.add(BlueprintFilterTypes.define(d,
						BlueprintFilterTypesTests.class.getClassLoader(), null, expressionService));
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

	@Test
	public void defineStandard() {
		// THEN
		// @formatter:off
		then(results)
			.as("Every standard filter type is available from its blueprint")
			.allSatisfy(r -> then(r).isInstanceOf(BlueprintFilterTypes.Defined.class))
			.extracting(BlueprintFilterTypes.Result::factoryUid)
			.as("Same filter types as the hand-written ones")
			.containsExactlyInAnyOrderElementsOf(StandardFilterTypes.definitions().stream()
					.map(FilterTypeDefinition::factoryUid).toList())
			;
		// @formatter:on
	}

	@ParameterizedTest
	@ValueSource(strings = { "standard-filters", "handbook-expressions" })
	public void simulateStandard(String fixture) throws IOException {
		// GIVEN
		final Map<String, FilterTypeDefinition> defs = new LinkedHashMap<>();
		for ( BlueprintFilterTypes.Result r : results ) {
			if ( r instanceof BlueprintFilterTypes.Defined(FilterTypeDefinition def, var _) ) {
				defs.put(def.factoryUid(), def);
			}
		}
		final SpelExpressionService expressionService = new SpelExpressionService();
		final SimulationEngine blueprintEngine = new SimulationEngine(
				new FilterTypeRegistry(defs.values()), expressionService);
		final SimulationEngine standardEngine = new SimulationEngine(
				new FilterTypeRegistry(StandardFilterTypes.definitions()), expressionService);
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
		then(summary(blueprint, true))
			.as("Same simulation with the blueprint filter types as with the standard ones")
			.isEqualTo(summary(standard, false))
			;
		// @formatter:on
	}

	private static Map<String, Object> summary(SimulationResult result, boolean blueprint) {
		final Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("messages",
				result.messages().stream().filter(m -> !HAND_WRITTEN_CODES.contains(m.code()))
						.filter(m -> !(blueprint && SimulationMessage.CODE_PENDING_TASKS.equals(m.code())
								&& JOIN_FILTER_ID.equals(m.filterId())))
						.map(SimulationMessage::code).toList());
		summary.put("stats", List.of(result.stats().processed(), result.stats().generated(),
				result.stats().output(), result.stats().discarded()));
		final List<Object> datum = new ArrayList<>();
		for ( DatumTrace t : result.datum() ) {
			datum.add(List.of(t.seq(), String.valueOf(t.input().sourceId()),
					t.steps().stream().map(s -> s.filterId() + ":" + s.status()).toList(),
					String.valueOf(t.output() != null ? t.output().samples() : null),
					String.valueOf(t.error())));
		}
		summary.put("datum", datum);
		return summary;
	}

}
