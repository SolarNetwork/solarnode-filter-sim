/* ==================================================================
 * SmokeFixtureTests.java - 19 Oct 2026 3:40:12 pm
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

package s10k.node.sim.filter.smoke.test;

import static org.assertj.core.api.BDDAssertions.then;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Run the smoke test fixtures on the JVM.
 *
 * <p>
 * Each fixture {@code <name>.json} in the {@code smoke} resources is a
 * simulation request, and {@code <name>.expected.json} is a summary of its
 * result: the message codes, statistics, and each datum's source ID, step
 * statuses, and output samples, with every value as a string. The
 * {@code nativeSmokeTest} Gradle task posts the same fixtures to the native
 * executable and compares the same summary, which shows missing runtime hints.
 * </p>
 *
 * <p>
 * When a result does not match, its summary is written to
 * {@code build/smoke/<name>.expected.json}, to review and copy over the
 * fixture's when the change is expected.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SmokeFixtureTests {

	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(SerializationFeature.INDENT_OUTPUT).build();

	private static final String FIXTURES = "/s10k/node/sim/filter/smoke/";

	private SimulationEngine engine;

	@BeforeEach
	public void setup() {
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	private static JsonNode resource(String name) throws IOException {
		try (InputStream in = SmokeFixtureTests.class.getResourceAsStream(FIXTURES + name)) {
			return JSON.readTree(in);
		}
	}

	/**
	 * Normalize a value to a string, with numbers in plain notation without
	 * trailing zeros.
	 */
	private static JsonNode normalized(JsonNode node) {
		if ( node.isObject() ) {
			ObjectNode result = JsonNodeFactory.instance.objectNode();
			for ( Map.Entry<String, JsonNode> e : node.properties() ) {
				if ( !e.getValue().isNull() ) {
					result.set(e.getKey(), normalized(e.getValue()));
				}
			}
			return result;
		} else if ( node.isArray() ) {
			ArrayNode result = JsonNodeFactory.instance.arrayNode();
			node.forEach(n -> result.add(normalized(n)));
			return result;
		} else if ( node.isNumber() ) {
			return JsonNodeFactory.instance
					.stringNode(new BigDecimal(node.asString()).stripTrailingZeros().toPlainString());
		}
		return JsonNodeFactory.instance.stringNode(node.asString());
	}

	/**
	 * Summarize a simulation result, the same way the {@code nativeSmokeTest}
	 * task does.
	 */
	private static JsonNode summary(JsonNode result) {
		ObjectNode summary = JsonNodeFactory.instance.objectNode();
		ArrayNode codes = summary.putArray("messages");
		result.path("messages").forEach(m -> codes.add(m.path("code").asString()));
		ObjectNode stats = summary.putObject("stats");
		for ( String key : List.of("processed", "generated", "output", "discarded") ) {
			stats.set(key, result.path("stats").path(key));
		}
		ArrayNode datum = summary.putArray("datum");
		for ( JsonNode trace : result.path("datum") ) {
			ObjectNode d = datum.addObject();
			d.set("seq", trace.path("seq"));
			d.set("sourceId", trace.path("input").path("sourceId"));
			ArrayNode steps = d.putArray("steps");
			trace.path("steps").forEach(
					s -> steps.add(s.path("filterId").asString() + ":" + s.path("status").asString()));
			d.set("error", trace.path("error"));
			d.set("output", trace.path("output").path("samples"));
			d.properties().removeIf(e -> e.getValue().isMissingNode() || e.getValue().isNull());
		}
		return normalized(summary);
	}

	private static List<String> expressionNames(SimulationRequest req) {
		List<String> result = new ArrayList<>();
		for ( FilterConfig filter : req.config().filters() ) {
			filter.settings().forEach((k, v) -> {
				if ( k.startsWith("expressionConfigs[") && k.endsWith("].name") ) {
					result.add(v);
				}
			});
		}
		return result;
	}

	private SimulationResult simulateFixture(String name, JsonNode expected) throws IOException {
		SimulationRequest req = JSON.treeToValue(resource(name + ".json"), SimulationRequest.class);
		SimulationResult result = engine.simulate(req);
		JsonNode actual = summary(JSON.valueToTree(result));
		if ( !actual.equals(expected) ) {
			Path out = Path.of("build", "smoke", name + ".expected.json");
			Files.createDirectories(out.getParent());
			Files.writeString(out, JSON.writeValueAsString(actual));
		}

		// @formatter:off
		then(actual)
			.as("Result of %s matches its expected summary", name)
			.isEqualTo(expected)
			;
		// @formatter:on
		return result;
	}

	@Test
	public void handbookExpressions() throws IOException {
		// GIVEN
		final String name = "handbook-expressions";
		JsonNode expected = resource(name + ".expected.json");
		SimulationRequest req = JSON.treeToValue(resource(name + ".json"), SimulationRequest.class);

		// WHEN
		simulateFixture(name, expected);

		// THEN
		// @formatter:off
		then(expected.path("messages").valueStream().map(JsonNode::asString))
			.as("No filter logged a problem or failed")
			.doesNotContain("FilterLog", "FilterError")
			;
		then(expected.path("datum").path(0).path("output").path("i").propertyNames())
			.as("Every expression produced a property")
			.containsAll(expressionNames(req).stream()
					.filter(n -> !expected.path("datum").path(0).path("output").path("s").has(n))
					.toList())
			;
		// @formatter:on
	}

	@Test
	public void standardFilters() throws IOException {
		// GIVEN
		final String name = "standard-filters";
		JsonNode expected = resource(name + ".expected.json");

		// WHEN
		simulateFixture(name, expected);

		// THEN
		// @formatter:off
		then(expected.path("messages").valueStream().map(JsonNode::asString))
			.as("No filter logged a problem or failed")
			.doesNotContain("FilterLog", "FilterError")
			;
		// @formatter:on
	}

}
