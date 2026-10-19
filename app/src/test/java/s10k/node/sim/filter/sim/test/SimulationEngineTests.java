/* ==================================================================
 * SimulationEngineTests.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.sim.test;

import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.assertj.core.api.BDDAssertions.within;
import static org.assertj.core.groups.Tuple.tuple;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.FilterStep.Status;
import s10k.node.sim.filter.domain.LocalStateChange;
import s10k.node.sim.filter.domain.LocalStateInfo;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationHistory;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.domain.UserMetadataConfig;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import s10k.node.sim.filter.sim.SimulationException;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Test cases for the {@link SimulationEngine} class.
 *
 * @author matt
 * @version 1.0
 */
public class SimulationEngineTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private static final String METER_DATUM = """
			{"created":"2025-10-01 00:00:00Z","sourceId":"/meter/1",
			"i":{"voltage_a":220,"voltage_b":222,"voltage_c":225,"watts":123.456},
			"a":{"wattHours":98765432}}""";

	/** A tariff schedule with a rate for each half of the year. */
	private static final String HALF_YEAR_SCHEDULE = """
			Month,Day,Weekday,Time,Rate
			Jan-Jun,,,,0.10
			Jul-Dec,,,,0.20
			""";

	// the middle of the day in the middle of each month, so the month is the same in every time zone
	private static final String MARCH_DATUM = """
			{"created":"2025-03-15 12:00:00Z","sourceId":"/meter/1","i":{"watts":100}}""";

	private static final String SEPTEMBER_DATUM = """
			{"created":"2025-09-15 12:00:00Z","sourceId":"/meter/1","i":{"watts":100}}""";

	private SimulationEngine engine;

	@BeforeEach
	public void setup() {
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	private static JsonNode json(String json) {
		return JSON.readTree(json);
	}

	private static FilterConfig chain(String id, String uid, String... memberIds) {
		return new FilterConfig(id, StandardFilterTypes.CHAIN, null, Map.of("uid", uid),
				List.of(memberIds));
	}

	private static Map<String, String> expressions(String uid, String... nameExpressionPairs) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", uid);
		s.put("expressionConfigsCount", String.valueOf(nameExpressionPairs.length / 2));
		for ( int i = 0; i < nameExpressionPairs.length; i += 2 ) {
			String prefix = "expressionConfigs[" + (i / 2) + "].";
			s.put(prefix + "name", nameExpressionPairs[i]);
			s.put(prefix + "datumPropertyTypeKey", "i");
			s.put(prefix + "expressionServiceId", SPEL);
			s.put(prefix + "expression", nameExpressionPairs[i + 1]);
		}
		return s;
	}

	private static FilterConfig expression(String id, String uid, String... nameExpressionPairs) {
		return new FilterConfig(id, StandardFilterTypes.EXPRESSION, null,
				expressions(uid, nameExpressionPairs), null);
	}

	private static Map<String, String> split(String uid, String sourceId) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", uid);
		if ( sourceId != null ) {
			s.put("sourceId", sourceId);
		}
		s.put("swallowInput", "false");
		s.put("propertySourceMappingsCount", "1");
		s.put("propertySourceMappings[0].key", "^watts$");
		s.put("propertySourceMappings[0].value", "/meter/1/power");
		return s;
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			SimulationHistory history, SimulationEnvironment environment, SimulationOptions options) {
		return new SimulationRequest(new SimConfig(filters, "main"), null, datum, history, environment,
				options);
	}

	private static SimulationRequest request(List<FilterConfig> filters, String... datum) {
		return request(filters, List.of(datum).stream().map(SimulationEngineTests::json).toList(), null,
				null, null);
	}

	private static Number number(Object o) {
		return (Number) o;
	}

	private static FilterConfig tariff(String id, String uid, String metadataServiceUid) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", uid);
		if ( metadataServiceUid != null ) {
			s.put("metadataServiceUid", metadataServiceUid);
		}
		return new FilterConfig(id, StandardFilterTypes.TARIFF, null, s, null);
	}

	private static ObjectNode tariffMetadata(String schedule) {
		ObjectNode meta = JSON.createObjectNode();
		meta.putObject("pm").putObject("tariffs").put("schedule", schedule);
		return meta;
	}

	private static SimulationEnvironment metadataEnvironment(JsonNode nodeMetadata,
			UserMetadataConfig... userMetadata) {
		return new SimulationEnvironment(null, null, nodeMetadata,
				userMetadata.length > 0 ? List.of(userMetadata) : null, null, null);
	}

	private static Object rate(DatumTrace trace) {
		return trace.output().samples().i().get("rate");
	}

	@Test
	public void expression() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "exp"),
						expression("exp", "EXP", "vavg", "avg({voltage_a, voltage_b, voltage_c})")),
				METER_DATUM);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum())
			.as("One datum processed")
			.hasSize(1)
			;
		DatumTrace trace = result.datum().getFirst();
		then(trace.steps())
			.as("Chain step, then nested expression step")
			.extracting(FilterStep::filterId, FilterStep::depth, FilterStep::status)
			.containsExactly(
					tuple("main", 0, Status.Changed),
					tuple("exp", 1, Status.Changed))
			;
		then(trace.output().samples().i())
			.as("Expression property added to output")
			.hasEntrySatisfying("vavg", v -> then(number(v).doubleValue())
					.isCloseTo(222.333, within(0.001)))
			;
		then(result.stats())
			.as("Datum processed and output")
			.returns(1, from(s -> s.processed()))
			.returns(1, from(s -> s.output()))
			.returns(0, from(s -> s.generated()))
			;
		// @formatter:on
	}

	@Test
	public void history() {
		// GIVEN
		SimulationHistory history = new SimulationHistory(List.of(json("""
				{"created":"2025-09-30 23:59:00Z","sourceId":"/meter/2","i":{"watts":7}}""")),
				List.of(json("""
						{"created":"2025-09-30 23:59:00Z","sourceId":"/meter/2","i":{"watts":5}}""")));
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "exp"),
						expression("exp", "EXP", "filtered", "latest('/meter/2').watts", "raw",
								"unfilteredLatest('/meter/2').watts")),
				List.of(json(METER_DATUM)), history, null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum().getFirst().output().samples().i())
			.as("Filtered history value from latest()")
			.hasEntrySatisfying("filtered", v -> then(number(v).intValue()).isEqualTo(7))
			.as("Unfiltered history value from unfilteredLatest()")
			.hasEntrySatisfying("raw", v -> then(number(v).intValue()).isEqualTo(5))
			;
		then(result.finalState().history())
			.as("Final history has the history datum and the output datum")
			.containsOnlyKeys("/meter/1", "/meter/2")
			;
		// @formatter:on
	}

	@Test
	public void localState() {
		// GIVEN
		SimulationEnvironment env = new SimulationEnvironment(null, """
				Key,Created,Modified,Type,Value
				counter,,,Int64,41
				""", null, null, null, null);
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "exp"),
						expression("exp", "EXP", "count",
								"saveLocalState('counter', localState('counter', 0) + 1)")),
				List.of(json(METER_DATUM)), null, env, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		DatumTrace trace = result.datum().getFirst();
		then(trace.output().samples().i())
			.as("Local State loaded from CSV as an Int64, then incremented")
			.hasEntrySatisfying("count", v -> then(number(v).longValue()).isEqualTo(42L))
			;
		then(trace.steps().get(1).localState())
			.as("Local State change attributed to the expression step")
			.hasSize(1)
			.first()
			.returns("counter", from(LocalStateChange::key))
			.returns(42L, from(c -> number(c.value()).longValue()))
			.returns(41L, from(c -> number(c.previousValue()).longValue()))
			;
		then(result.finalState().localState())
			.as("Final Local State has the updated value")
			.extracting(LocalStateInfo::key, s -> number(s.value()).longValue())
			.containsExactly(tuple("counter", 42L))
			;
		then(result.finalState().localStateCsv())
			.as("Final Local State CSV in the SolarNode format")
			.startsWith("Key,Created,Modified,Type,Value")
			.contains(",Int64,42")
			;
		// @formatter:on
	}

	@Test
	public void requiredOpMode_inactive() {
		// GIVEN
		Map<String, String> settings = expressions("EXP", "x", "1");
		settings.put("requiredOperationalMode", "Boost");
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "exp"),
						new FilterConfig("exp", StandardFilterTypes.EXPRESSION, null, settings, null)),
				List.of(json(METER_DATUM)), null,
				new SimulationEnvironment(List.of(), null, null, null, null, null), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		DatumTrace trace = result.datum().getFirst();
		then(trace.steps())
			.as("Expression skipped because the required mode is not active")
			.extracting(FilterStep::filterId, FilterStep::status)
			.containsExactly(
					tuple("main", Status.Unchanged),
					tuple("exp", Status.Skipped))
			;
		then(trace.output().samples().i())
			.as("Expression property not added")
			.doesNotContainKey("x")
			;
		// @formatter:on
	}

	@Test
	public void requiredOpMode_active() {
		// GIVEN
		Map<String, String> settings = expressions("EXP", "x", "1");
		settings.put("requiredOperationalMode", "Boost");
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "exp"),
						new FilterConfig("exp", StandardFilterTypes.EXPRESSION, null, settings, null)),
				List.of(json(METER_DATUM)), null,
				new SimulationEnvironment(List.of(" BOOST "), null, null, null, null, null), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum().getFirst().output().samples().i())
			.as("Expression applied because the required mode is active (case insensitive)")
			.containsKey("x")
			;
		then(result.finalState().opModes())
			.as("Starting mode lowercased, like SolarNode")
			.containsExactly("boost")
			;
		// @formatter:on
	}

	@Test
	public void nestedChain() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("main", "Chain - Main", "a", "b", "d"),
				expression("a", "A", "x", "1"), chain("b", "Chain - B", "c"),
				expression("c", "C", "y", "x + 1"), expression("d", "D", "z", "y + 1")), METER_DATUM);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		DatumTrace trace = result.datum().getFirst();
		then(trace.steps())
			.as("Steps in execution order, with nesting depth")
			.extracting(FilterStep::filterId, FilterStep::depth)
			.containsExactly(
					tuple("main", 0),
					tuple("a", 1),
					tuple("b", 1),
					tuple("c", 2),
					tuple("d", 1))
			;
		then(trace.output().samples().i())
			.as("Each filter saw the previous filter's output")
			.hasEntrySatisfying("x", v -> then(number(v).intValue()).isEqualTo(1))
			.hasEntrySatisfying("y", v -> then(number(v).intValue()).isEqualTo(2))
			.hasEntrySatisfying("z", v -> then(number(v).intValue()).isEqualTo(3))
			;
		// @formatter:on
	}

	@Test
	public void split_generatesDatum() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "split"), new FilterConfig("split",
						StandardFilterTypes.SPLIT, null, split("SPLIT", "^/meter/1$"), null)),
				METER_DATUM);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Input datum and generated datum processed")
			.hasSize(2)
			;
		then(result.datum().getFirst().steps().get(1).generated())
			.as("Generated datum attributed to the split step")
			.containsExactly(2)
			;
		DatumTrace generated = result.datum().get(1);
		then(generated.origin())
			.as("Generated datum origin")
			.returns(DatumOrigin.Type.Generated, from(DatumOrigin::type))
			.returns(1, from(DatumOrigin::parentSeq))
			.returns("split", from(DatumOrigin::filterId))
			;
		then(generated.input().sourceId())
			.as("Generated datum source")
			.isEqualTo("/meter/1/power")
			;
		then(generated.steps())
			.as("Generated datum processed by the main chain, which skips the split")
			.extracting(FilterStep::filterId, FilterStep::status)
			.containsExactly(
					tuple("main", Status.Unchanged),
					tuple("split", Status.Skipped))
			;
		then(result.stats().generated())
			.as("Generated count")
			.isEqualTo(1)
			;
		// @formatter:on
	}

	private static FilterConfig splitTo(String id, String sourceId, String outputSourceId) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", id);
		s.put("sourceId", "^" + sourceId + "$");
		s.put("swallowInput", "false");
		s.put("propertySourceMappingsCount", "1");
		s.put("propertySourceMappings[0].key", "^watts$");
		s.put("propertySourceMappings[0].value", outputSourceId);
		return new FilterConfig(id, StandardFilterTypes.SPLIT, null, s, null);
	}

	@Test
	public void split_loopDetected() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("main", "Chain - Main", "splitA", "splitB"),
				splitTo("splitA", "/meter/1", "/meter/2"), splitTo("splitB", "/meter/2", "/meter/1")),
				List.of(json(METER_DATUM)), null, null,
				new SimulationOptions(null, 3, null, null, null, null, null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Input datum and 3 generations of generated datum processed")
			.hasSize(4)
			;
		then(result.messages())
			.as("Generation depth limit reported")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(tuple(SimulationMessage.CODE_GENERATION_DEPTH_LIMIT, "splitB"))
			;
		then(result.messages().getFirst().message())
			.as("Message has the generation path")
			.contains("/meter/1 → /meter/2 → /meter/1 → /meter/2 → /meter/1")
			;
		// @formatter:on
	}

	@Test
	public void unsupportedFilter() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("main", "Chain - Main", "poa"),
				new FilterConfig("poa", "net.solarnetwork.node.datum.filter.pvlib.poa", null,
						Map.of("uid", "POA"), null)),
				METER_DATUM);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Unsupported filter reported")
			.extracting(SimulationMessage::code)
			.containsExactly(SimulationMessage.CODE_UNSUPPORTED_FILTER)
			;
		then(result.datum().getFirst().steps())
			.as("Unsupported filter passed the datum through")
			.extracting(FilterStep::filterId, FilterStep::status)
			.containsExactly(
					tuple("main", Status.Unchanged),
					tuple("poa", Status.Unsupported))
			;
		// @formatter:on
	}

	@Test
	public void externalFilter() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "ext", "exp"),
						new FilterConfig("ext", FilterConfig.EXTERNAL_FACTORY_UID, null,
								Map.of("uid", "Elsewhere"), null),
						expression("exp", "EXP", "x", "1")),
				METER_DATUM);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("External filter reported")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(tuple(SimulationMessage.CODE_EXTERNAL_FILTER, "ext"))
			;
		then(result.datum().getFirst().steps())
			.as("External filter passed the datum through, and the chain continued")
			.extracting(FilterStep::filterId, FilterStep::status)
			.containsExactly(
					tuple("main", Status.Changed),
					tuple("ext", Status.Unsupported),
					tuple("exp", Status.Changed))
			;
		// @formatter:on
	}

	@Test
	public void unknownSetting() {
		// GIVEN
		Map<String, String> settings = expressions("EXP", "x", "1");
		settings.put("notASetting", "foo");
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "exp"),
						new FilterConfig("exp", StandardFilterTypes.EXPRESSION, null, settings, null)),
				METER_DATUM);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Unknown setting reported")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(tuple(SimulationMessage.CODE_UNKNOWN_SETTING, "exp"))
			;
		// @formatter:on
	}

	@Test
	public void debugDetail() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "exp"), expression("exp", "EXP", "x", "1")),
				List.of(json(METER_DATUM), json(METER_DATUM.replace("00:00:00Z", "00:01:00Z"))), null,
				null, new SimulationOptions(null, null, null, SimulationOptions.Detail.Debug, null, null,
						null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum().getFirst().startState())
			.as("First datum starts with an empty history and the second datum queued")
			.isNotNull()
			.returns(Map.of(), from(s -> s.history()))
			.returns(List.of(2), from(s -> s.queue()))
			;
		then(result.datum().get(1).startState().history())
			.as("Second datum starts with the first datum output in the history")
			.containsOnlyKeys("/meter/1")
			;
		// @formatter:on
	}

	@Test
	public void tariff_nodeMetadata() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "tariff"), tariff("tariff", "Tariff", null)),
				List.of(json(MARCH_DATUM), json(SEPTEMBER_DATUM)), null,
				metadataEnvironment(tariffMetadata(HALF_YEAR_SCHEDULE)), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Only the time zone limitation reported")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(tuple(SimulationMessage.CODE_FILTER_LIMITATION, "tariff"))
			;
		then(result.datum())
			.as("Rate for each datum's month added, from the Node Metadata Service schedule")
			.extracting(t -> number(rate(t)).doubleValue())
			.containsExactly(0.10, 0.20)
			;
		// @formatter:on
	}

	@Test
	public void tariff_userMetadata() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "tariff"), tariff("tariff", "Tariff", "Tariffs")),
				List.of(json(MARCH_DATUM), json(SEPTEMBER_DATUM)), null,
				metadataEnvironment(tariffMetadata("Month,Day,Weekday,Time,Rate\n,,,,0.99"),
						new UserMetadataConfig("Tariffs", tariffMetadata(HALF_YEAR_SCHEDULE))),
				null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No problem reported")
			.extracting(SimulationMessage::code)
			.doesNotContain(SimulationMessage.CODE_FILTER_PROBLEM)
			;
		then(result.datum())
			.as("Rate added from the schedule of the User Metadata Service with the Service Name")
			.extracting(t -> number(rate(t)).doubleValue())
			.containsExactly(0.10, 0.20)
			;
		// @formatter:on
	}

	@Test
	public void tariff_unknownMetadataService() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "tariff"), tariff("tariff", "Tariff", "Tarifs")),
				List.of(json(MARCH_DATUM)), null, metadataEnvironment(null,
						new UserMetadataConfig("Tariffs", tariffMetadata(HALF_YEAR_SCHEDULE))),
				null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Missing schedule reported")
			.filteredOn(m -> SimulationMessage.CODE_FILTER_PROBLEM.equals(m.code()))
			.singleElement()
			.returns("tariff", from(SimulationMessage::filterId))
			.extracting(SimulationMessage::message)
			.asString()
			.as("Message names the Service Name and the metadata services")
			.contains("\"Tarifs\"", "Node Metadata Service, Tariffs")
			;
		then(result.datum().getFirst().steps())
			.as("Filter changed nothing")
			.extracting(FilterStep::filterId, FilterStep::status)
			.containsExactly(
					tuple("main", Status.Unchanged),
					tuple("tariff", Status.Unchanged))
			;
		// @formatter:on
	}

	@Test
	public void tariff_scheduleArray() {
		// GIVEN
		ObjectNode meta = JSON.createObjectNode();
		ArrayNode schedule = meta.putObject("pm").putObject("tariffs").putArray("schedule");
		schedule.addArray().add("Month").add("Day").add("Weekday").add("Time").add("Rate");
		schedule.addArray().add("Jan-Jun").addNull().add("").add("").add(0.10);
		schedule.addArray().add("Jul-Dec").add("").addNull().addNull().add("0.20");
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "tariff"), tariff("tariff", "Tariff", null)),
				List.of(json(MARCH_DATUM), json(SEPTEMBER_DATUM)), null, metadataEnvironment(meta),
				null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No problem reported")
			.extracting(SimulationMessage::code)
			.doesNotContain(SimulationMessage.CODE_FILTER_PROBLEM)
			;
		then(result.datum())
			.as("Rate added from a schedule stored as a JSON array of arrays")
			.extracting(t -> number(rate(t)).doubleValue())
			.containsExactly(0.10, 0.20)
			;
		// @formatter:on
	}

	@Test
	public void tariff_scheduleObject() {
		// GIVEN
		ObjectNode meta = JSON.createObjectNode();
		meta.putObject("pm").putObject("tariffs").putObject("schedule").put("Rate", 0.10);
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "tariff"), tariff("tariff", "Tariff", null)),
				List.of(json(MARCH_DATUM)), null, metadataEnvironment(meta), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Schedule that is not CSV text or an array of arrays reported")
			.filteredOn(m -> SimulationMessage.CODE_FILTER_PROBLEM.equals(m.code()))
			.singleElement()
			.extracting(SimulationMessage::message)
			.asString()
			.contains("/pm/tariffs/schedule", "Node Metadata Service", "JSON object")
			;
		then(result.datum().getFirst().output().samples().i())
			.as("No rate added")
			.doesNotContainKey("rate")
			;
		// @formatter:on
	}

	@Test
	public void expression_metadata() {
		// GIVEN
		ObjectNode meta = tariffMetadata("Month,Day,Weekday,Time,Rate\n,,,,0.15");
		((ObjectNode) meta.get("pm")).putObject("site").put("factor", 2.5);
		SimulationRequest req = request(List.of(chain("main", "Chain - Main", "exp"),
				expression("exp", "EXP", "scaled", "metadataAtPath('/pm/site/factor') * watts", "price",
						"tariffSchedule('Tariff').resolveTariff(now(), null).rates['rate'].amount"),
				tariff("tariff", "Tariff", null)), List.of(json(METER_DATUM)), null,
				metadataEnvironment(meta), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum().getFirst().output().samples().i())
			.as("Node metadata available to expressions")
			.hasEntrySatisfying("scaled", v -> then(number(v).doubleValue())
					.isCloseTo(308.64, within(0.001)))
			.as("Tariff schedules available to expressions, from filters not in any chain")
			.hasEntrySatisfying("price", v -> then(number(v).doubleValue())
					.isEqualTo(0.15))
			;
		// @formatter:on
	}

	@Test
	public void metadata_invalid() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("main", "Chain - Main")),
				List.of(json(METER_DATUM)), null, metadataEnvironment(json("\"schedule\"")), null);

		// THEN
		// @formatter:off
		thenThrownBy(() -> engine.simulate(req))
			.as("Metadata must be an object")
			.isInstanceOf(SimulationException.class)
			.hasMessage("Node metadata is not a JSON object.")
			;
		// @formatter:on
	}

	@Test
	public void metadata_messages() {
		// GIVEN
		ObjectNode meta = JSON.createObjectNode();
		meta.put("nodeId", 123);
		meta.putObject("m").put("name", "Test");
		SimulationRequest req = request(List.of(chain("main", "Chain - Main")),
				List.of(json(METER_DATUM)), null,
				metadataEnvironment(meta, new UserMetadataConfig("Node Metadata Service", null),
						new UserMetadataConfig(" ", null)),
				null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Ignored property, duplicate Service Name, and missing Service Name reported")
			.extracting(SimulationMessage::code, SimulationMessage::message)
			.containsExactly(
					tuple(SimulationMessage.CODE_IGNORED_METADATA,
							"Node metadata has the property nodeId, which SolarNode ignores. Metadata has only m, pm, and t properties."),
					tuple(SimulationMessage.CODE_DUPLICATE_UID,
							"More than one metadata service has the Service Name Node Metadata Service, so filters use the first one."),
					tuple(SimulationMessage.CODE_MISSING_UID,
							"User Metadata Service 2 has no Service Name, so filters cannot find it."))
			;
		// @formatter:on
	}

	private static FilterConfig timeout(String id, long timeoutMillis) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Timeout");
		s.put("sourceId", "^/meter/1$");
		s.put("outputSourceId", "/meter/1/timeout");
		s.put("timeoutMillis", String.valueOf(timeoutMillis));
		return new FilterConfig(id, StandardFilterTypes.TIMEOUT, null, s, null);
	}

	private static String meterDatum(String created) {
		return """
				{"created":"%s","sourceId":"/meter/1","i":{"watts":1}}""".formatted(created);
	}

	@Test
	public void scheduledTask_betweenDatum() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "timeout"), timeout("timeout", 60_000)),
				meterDatum("2025-10-01 00:00:00Z"), meterDatum("2025-10-01 00:00:30Z"),
				meterDatum("2025-10-01 00:03:00Z"));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Timeouts fire a minute after the last datum, and again each minute, before the next datum")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp())
			.containsExactly(
					tuple("/meter/1", Instant.parse("2025-10-01T00:00:00Z")),
					tuple("/meter/1", Instant.parse("2025-10-01T00:00:30Z")),
					tuple("/meter/1/timeout", Instant.parse("2025-10-01T00:01:30Z")),
					tuple("/meter/1/timeout", Instant.parse("2025-10-01T00:02:30Z")),
					tuple("/meter/1", Instant.parse("2025-10-01T00:03:00Z")))
			;
		then(result.datum().get(2).origin())
			.as("Timeout datum come from the filter's scheduled task")
			.returns(DatumOrigin.Type.Scheduled, from(DatumOrigin::type))
			.returns("timeout", from(DatumOrigin::filterId))
			;
		then(result.messages())
			.as("The next timeout is still waiting")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(tuple(SimulationMessage.CODE_PENDING_TASKS, "timeout"))
			;
		// @formatter:on
	}

	@Test
	public void scheduledTask_runAfter() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "timeout"), timeout("timeout", 60_000)),
				List.of(json(meterDatum("2025-10-01 00:00:00Z"))), null, null,
				new SimulationOptions(null, null, null, null, null, null, Duration.ofMinutes(2)));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Timeouts due by the end of the extra time run after the last datum")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp())
			.containsExactly(
					tuple("/meter/1", Instant.parse("2025-10-01T00:00:00Z")),
					tuple("/meter/1/timeout", Instant.parse("2025-10-01T00:01:00Z")),
					tuple("/meter/1/timeout", Instant.parse("2025-10-01T00:02:00Z")))
			;
		then(result.stats())
			.as("Timeout datum counted as generated")
			.returns(2, from(s -> s.generated()))
			;
		// @formatter:on
	}

	@Test
	public void placeholdersAndDatumMetadata() {
		// GIVEN
		ObjectNode meta = JSON.createObjectNode();
		meta.putObject("pm").putObject("panel").put("efficiency", 0.2);
		SimulationEnvironment env = new SimulationEnvironment(null, null, null, null,
				Map.of("factor", "2"), Map.of("/meter/1", meta));
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "exp"),
						expression("exp", "EXP", "doubled", "watts * factor", "panel",
								"meta(sourceId).metadataAtPath('/pm/panel/efficiency') * 100")),
				List.of(json(METER_DATUM)), null, env, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum().getFirst().output().samples().i())
			.as("Placeholder available to expressions, as a number")
			.hasEntrySatisfying("doubled", v -> then(number(v).doubleValue())
					.isCloseTo(246.912, within(0.001)))
			.as("Datum metadata available to expressions")
			.hasEntrySatisfying("panel", v -> then(number(v).doubleValue())
					.isCloseTo(20.0, within(0.001)))
			;
		// @formatter:on
	}

	private static String wattsDatum(String created, int watts) {
		return """
				{"created":"%s","sourceId":"/meter/1","i":{"watts":%d}}""".formatted(created, watts);
	}

	@Test
	public void opModeExpiry_simulatedClock() {
		// GIVEN
		Map<String, String> opMode = new LinkedHashMap<>();
		opMode.put("uid", "Mode");
		opMode.put("expressionConfigsCount", "1");
		opMode.put("expressionConfigs[0].operationalMode", "boost");
		opMode.put("expressionConfigs[0].expressionServiceId", SPEL);
		opMode.put("expressionConfigs[0].expression", "watts > 100");
		opMode.put("expressionConfigs[0].expireSeconds", "60");
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "mode", "exp"),
						new FilterConfig("mode", StandardFilterTypes.OP_MODE, null, opMode, null),
						expression("exp", "EXP", "boosted", "isOpMode('boost') ? 1 : 0")),
				wattsDatum("2025-10-01 00:00:00Z", 200), wattsDatum("2025-10-01 00:00:30Z", 50),
				wattsDatum("2025-10-01 00:01:30Z", 50));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Mode active until it expires a minute later, in simulated time")
			.extracting(t -> number(t.output().samples().i().get("boosted")).intValue())
			.containsExactly(1, 1, 0)
			;
		then(result.finalState().opModes())
			.as("Expired mode not active at the end")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void downsampleDuration_simulatedClock() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("main", "Chain - Main", "down"),
						new FilterConfig("down", StandardFilterTypes.DOWNSAMPLE, null,
								Map.of("uid", "Down", "sampleDurationSecs", "60"), null)),
				wattsDatum("2025-10-01 00:00:10Z", 1), wattsDatum("2025-10-01 00:00:30Z", 2),
				wattsDatum("2025-10-01 00:00:50Z", 3), wattsDatum("2025-10-01 00:01:10Z", 4));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Datum grouped into minutes by their timestamps")
			.extracting(t -> t.output() != null ? new TreeMap<>(t.output().samples().i()).toString()
					: null)
			.containsExactly(null, null, null, "{watts=2, watts_max=3, watts_min=1}")
			;
		// @formatter:on
	}

	@Test
	public void noEntryPoint() {
		// GIVEN
		SimulationRequest req = new SimulationRequest(
				new SimConfig(List.of(expression("exp", "EXP", "x", "1")), null), null,
				List.of(json(METER_DATUM)), null, null, null);

		// THEN
		// @formatter:off
		thenThrownBy(() -> engine.simulate(req))
			.as("Entry point required")
			.isInstanceOf(SimulationException.class)
			.hasMessageContaining("entry point")
			;
		// @formatter:on
	}

}
