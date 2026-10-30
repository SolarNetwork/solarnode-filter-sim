/* ==================================================================
 * OpModeInvokerTests.java - 30 Oct 2026 4:05:12 pm
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
import static org.assertj.core.groups.Tuple.tuple;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.DatumSourceConfig;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.OpModeInvokerConfig;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardDatumSourceTypes;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for Operational Mode Datum Source Invokers.
 *
 * @author matt
 * @version 1.0
 */
public class OpModeInvokerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private static final String MODE = "boost";

	/** A schedule after the end of each test, so only the invoker polls. */
	private static final String HOURLY = "0 0 * * * ?";

	private SimulationEngine engine;

	@BeforeEach
	public void setup() {
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	private static JsonNode meter(String time, int watts) {
		return JSON.readTree("""
				{"created":"2025-10-01 %sZ","sourceId":"/meter/1","i":{"watts":%d}}""".formatted(time,
				watts));
	}

	private static Instant at(String time) {
		return Instant.parse("2025-10-01T" + time + "Z");
	}

	private static FilterConfig chain(String... memberIds) {
		return new FilterConfig("main", StandardFilterTypes.CHAIN, null, Map.of("uid", "Chain - Main"),
				List.of(memberIds));
	}

	private static DatumSourceConfig fixed(String id, String uid, String sourceId) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("schedule", HOURLY);
		s.put("jobService.datumDataSource.uid", uid);
		s.put("jobService.datumDataSource.sourceId", sourceId);
		s.put("jobService.datumDataSource.propertyConfigsCount", "1");
		s.put("jobService.datumDataSource.propertyConfigs[0].propertyKey", "watts");
		s.put("jobService.datumDataSource.propertyConfigs[0].propertyTypeKey", "i");
		s.put("jobService.datumDataSource.propertyConfigs[0].config", "100");
		return new DatumSourceConfig(id, StandardDatumSourceTypes.FIXED, null, s);
	}

	/**
	 * An invoker of {@link #MODE}, with a configuration per pair of datum
	 * source Service Name and schedule.
	 */
	private static OpModeInvokerConfig invoker(String id, String mode, String... uidSchedules) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Invoker");
		if ( mode != null ) {
			s.put("operationalMode", mode);
		}
		s.put("configurationsCount", String.valueOf(uidSchedules.length / 2));
		for ( int i = 0; i + 1 < uidSchedules.length; i += 2 ) {
			s.put("configurations[%d].uid".formatted(i / 2), uidSchedules[i]);
			s.put("configurations[%d].schedule".formatted(i / 2), uidSchedules[i + 1]);
		}
		return new OpModeInvokerConfig(id, null, s);
	}

	/**
	 * An Operational Mode filter that enables {@link #MODE} while the meter's
	 * watts are over 100.
	 */
	private static FilterConfig opModeFilter() {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Mode");
		s.put("sourceId", "^/meter/1$");
		s.put("expressionConfigsCount", "1");
		s.put("expressionConfigs[0].operationalMode", MODE);
		s.put("expressionConfigs[0].expressionServiceId", SPEL);
		s.put("expressionConfigs[0].expression", "watts > 100");
		return new FilterConfig("mode", StandardFilterTypes.OP_MODE, null, s, null);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			List<DatumSourceConfig> sources, List<OpModeInvokerConfig> invokers, List<String> opModes) {
		return new SimulationRequest(new SimConfig(filters, "main"), null, datum, sources, invokers,
				null, new SimulationEnvironment(opModes, null, null, null, null, null, null, null, null),
				null);
	}

	private static List<String> codes(SimulationResult result) {
		return result.messages().stream().map(SimulationMessage::code).toList();
	}

	@Test
	public void startingMode_polls() {
		// GIVEN
		SimulationRequest req = request(List.of(chain()),
				List.of(meter("00:00:00", 1), meter("00:01:00", 2)),
				List.of(fixed("fixed", "Fixed", "/fixed/1"), fixed("other", "Other", "/other/1")),
				List.of(invoker("inv", MODE, "fixed", "20")), List.of(MODE));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("Only the other datum source was not polled")
			.containsExactly(SimulationMessage.CODE_DATUM_SOURCE_POLLS)
			;
		then(result.messages().getFirst().datumSourceId())
			.as("The message is about the datum source the invoker did not read")
			.isEqualTo("other")
			;
		then(result.datum())
			.as("The invoker reads the matching datum source (by Service Name, ignoring case) every 20 seconds while its mode is active, from the start")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp(), t -> t.origin().type())
			.containsExactly(
					tuple("/meter/1", at("00:00:00"), DatumOrigin.Type.Input),
					tuple("/fixed/1", at("00:00:00"), DatumOrigin.Type.Polled),
					tuple("/fixed/1", at("00:00:20"), DatumOrigin.Type.Polled),
					tuple("/fixed/1", at("00:00:40"), DatumOrigin.Type.Polled),
					tuple("/meter/1", at("00:01:00"), DatumOrigin.Type.Input))
			;
		then(result.datum().get(1).origin())
			.as("Polled origin names the datum source and the invoker")
			.returns("fixed", from(DatumOrigin::datumSourceId))
			.returns("inv", from(DatumOrigin::invokerId))
			;
		then(result.stats())
			.as("Polled datum counted")
			.returns(3, from(s -> s.polled()))
			;
		// @formatter:on
	}

	@Test
	public void opModeFilter_activatesInvoker() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("mode"), opModeFilter()),
				List.of(meter("00:00:00", 50), meter("00:00:30", 200), meter("00:01:00", 2)),
				List.of(fixed("fixed", "Fixed", "/fixed/1")),
				List.of(invoker("inv", MODE, "Fixed", "0/10 * * * * ?")), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages: the invoker's tasks never end, so they are not pending")
			.isEmpty()
			;
		then(result.datum())
			.as("The invoker polls on its cron schedule once the filter enables its mode")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp(), t -> t.origin().type())
			.containsExactly(
					tuple("/meter/1", at("00:00:00"), DatumOrigin.Type.Input),
					tuple("/meter/1", at("00:00:30"), DatumOrigin.Type.Input),
					tuple("/fixed/1", at("00:00:40"), DatumOrigin.Type.Polled),
					tuple("/fixed/1", at("00:00:50"), DatumOrigin.Type.Polled),
					tuple("/meter/1", at("00:01:00"), DatumOrigin.Type.Input))
			;
		then(result.datum().get(2).origin())
			.as("Polled by the invoker, not a scheduled task of the filter that enabled the mode")
			.returns(null, from(DatumOrigin::filterId))
			.returns("inv", from(DatumOrigin::invokerId))
			;
		// @formatter:on
	}

	@Test
	public void modeNeverActive_info() {
		// GIVEN
		SimulationRequest req = request(List.of(chain()), List.of(meter("00:00:00", 1)),
				List.of(fixed("fixed", "Fixed", "/fixed/1")),
				List.of(invoker("inv", MODE, "Fixed", "20")), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("The datum source and the invoker did not poll")
			.extracting(SimulationMessage::code, SimulationMessage::datumSourceId)
			.containsExactly(
					tuple(SimulationMessage.CODE_DATUM_SOURCE_POLLS, "fixed"),
					tuple(SimulationMessage.CODE_INVOKER_RUNS, "inv"))
			;
		then(result.messages().getLast().message())
			.as("The invoker's mode was never active")
			.isEqualTo("The Operational Mode Invoker \"Invoker\" did not poll, as its mode \"boost\" was never active.")
			;
		// @formatter:on
	}

	@Test
	public void invalidConfigurations() {
		// GIVEN
		SimulationRequest req = request(List.of(chain()), List.of(meter("00:00:00", 1)),
				List.of(fixed("fixed", "Fixed", "/fixed/1")),
				List.of(invoker("a", null),
						invoker("b", MODE, "Fixed", "", "Fixed", "nope", "Fixed", "0", "Unknown", "20"),
						invoker("fixed", MODE)),
				List.of(MODE));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Each problem is a warning about its invoker")
			.extracting(SimulationMessage::datumSourceId, SimulationMessage::message)
			.contains(
					tuple("a", "The Operational Mode Invoker \"Invoker\" has no Mode, so it never polls."),
					tuple("a", "The Operational Mode Invoker \"Invoker\" has no Data Source Configurations, so it never polls."),
					tuple("b", "The Operational Mode Invoker \"Invoker\" Data Source Configuration 1 has no Schedule, so it never polls."),
					tuple("b", "The Operational Mode Invoker \"Invoker\" Data Source Configuration 2 has the Schedule \"nope\", which is not a valid period or cron expression, so it never polls."),
					tuple("b", "The Operational Mode Invoker \"Invoker\" Data Source Configuration 3 has the Schedule 0, which a node runs without a pause, so the simulator does not run it."),
					tuple("b", "The Operational Mode Invoker \"Invoker\" Data Source Configuration 4 matches no datum source, so it polls nothing."),
					tuple(null, "The Operational Mode Invoker \"Invoker\" has the id \"fixed\", which another invoker or datum source has, so it is left out."))
			;
		then(result.datum())
			.as("Nothing polled")
			.hasSize(1)
			;
		// @formatter:on
	}

}
