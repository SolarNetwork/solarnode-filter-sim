/* ==================================================================
 * InstructionHandlerTests.java - 29 Oct 2026 2:40:00 pm
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

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.groups.Tuple.tuple;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.InstructionHandlerInfo;
import s10k.node.sim.filter.domain.InstructionInfo;
import s10k.node.sim.filter.domain.InstructionRun;
import s10k.node.sim.filter.domain.NodeControlConfig;
import s10k.node.sim.filter.domain.OpModeChange;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.domain.UserMetadataConfig;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimMetadataService;
import s10k.node.sim.filter.sim.SimulationEngine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for the simulated node components that handle instructions,
 * besides the Control Conductors: the operational modes service, the metadata
 * services, and the simulated controls.
 *
 * @author matt
 * @version 1.0
 */
public class InstructionHandlerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private static final String MODE = "boost";

	private SimulationEngine engine;

	@BeforeEach
	public void setup() {
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	private static JsonNode datum(String sourceId, String time) {
		return JSON.readTree("""
				{"created":"2025-10-01 %sZ","sourceId":"%s","i":{"watts":1}}""".formatted(time,
				sourceId));
	}

	private static Instant at(String time) {
		return Instant.parse("2025-10-01T" + time + "Z");
	}

	private static FilterConfig chain(String... memberIds) {
		return new FilterConfig("main", StandardFilterTypes.CHAIN, null, Map.of("uid", "Chain - Main"),
				List.of(memberIds));
	}

	/**
	 * An Instructor that issues one instruction for each {@code /trigger}
	 * datum, with plain parameters.
	 */
	private static FilterConfig instructor(String topic, String... nameValuePairs) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Instructor");
		s.put("sourceId", "^/trigger$");
		s.put("instructorConfigsCount", "1");
		s.put("instructorConfigs[0].predicate.expressionServiceId", SPEL);
		s.put("instructorConfigs[0].predicate.expression", "true");
		s.put("instructorConfigs[0].instructionsCount", "1");
		s.put("instructorConfigs[0].instructions[0].topic", topic);
		s.put("instructorConfigs[0].instructions[0].parametersCount",
				String.valueOf(nameValuePairs.length / 2));
		for ( int i = 0; i < nameValuePairs.length; i += 2 ) {
			String prefix = "instructorConfigs[0].instructions[0].parameters[" + (i / 2) + "].";
			s.put(prefix + "name", nameValuePairs[i]);
			s.put(prefix + "expression", nameValuePairs[i + 1]);
		}
		return new FilterConfig("instr", StandardFilterTypes.INSTRUCTOR, null, s, null);
	}

	/**
	 * An Expression filter for {@code /meter/1} that requires {@link #MODE}.
	 */
	private static FilterConfig boosted() {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Boost");
		s.put("sourceId", "^/meter/1$");
		s.put("requiredOperationalMode", MODE);
		s.put("expressionConfigsCount", "1");
		s.put("expressionConfigs[0].name", "boosted");
		s.put("expressionConfigs[0].datumPropertyTypeKey", "i");
		s.put("expressionConfigs[0].expressionServiceId", SPEL);
		s.put("expressionConfigs[0].expression", "watts * 2");
		return new FilterConfig("boost", StandardFilterTypes.EXPRESSION, null, s, null);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			SimulationEnvironment env, SimulationOptions options) {
		return new SimulationRequest(new SimConfig(filters, "main"), null, datum, null, env, options);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum) {
		return request(filters, datum, null, null);
	}

	private static SimulationEnvironment opModes(String... modes) {
		return new SimulationEnvironment(List.of(modes), null, null, null, null, null, null, null);
	}

	private static SimulationOptions runAfter(Duration after) {
		return new SimulationOptions(null, null, null, null, null, null, after, null, null);
	}

	private static FilterStep step(DatumTrace trace, String filterId) {
		return trace.steps().stream().filter(s -> s.filterId().equals(filterId)).findFirst()
				.orElseThrow();
	}

	private static InstructionHandlerInfo handler(InstructionHandlerInfo.Type type, String name) {
		return new InstructionHandlerInfo(type, name);
	}

	@Test
	public void enableOperationalModes_withExpiration() {
		// GIVEN
		final String expiration = String.valueOf(at("00:02:00").toEpochMilli());
		SimulationRequest req = request(
				List.of(chain("instr", "boost"),
						instructor("EnableOperationalModes", "OpMode", MODE, "Expiration", expiration),
						boosted()),
				List.of(datum("/trigger", "00:00:00"), datum("/meter/1", "00:01:00"),
						datum("/meter/1", "00:03:00")));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		FilterStep instr = step(result.datum().getFirst(), "instr");
		then(instr.instructions())
			.as("The operational modes service completed the instruction")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple("EnableOperationalModes", "Completed",
					handler(InstructionHandlerInfo.Type.OperationalModes, null)))
			;
		then(instr.opModes())
			.as("The Instructor's step enabled the mode")
			.isEqualTo(new OpModeChange(Set.of(MODE), Set.of()))
			;
		then(step(result.datum().get(1), "boost").status())
			.as("The filter that requires the mode applies while it is active")
			.isEqualTo(FilterStep.Status.Changed)
			;
		then(step(result.datum().get(2), "boost").status())
			.as("The filter is skipped once the mode has expired")
			.isEqualTo(FilterStep.Status.Skipped)
			;
		// @formatter:on
	}

	@Test
	public void disableOperationalModes() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr", "boost"), instructor("DisableOperationalModes", "OpMode", MODE),
						boosted()),
				List.of(datum("/meter/1", "00:00:00"), datum("/trigger", "00:01:00"),
						datum("/meter/1", "00:02:00")),
				opModes(MODE), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "boost").status())
			.as("The filter applies while the starting mode is active")
			.isEqualTo(FilterStep.Status.Changed)
			;
		FilterStep instr = step(result.datum().get(1), "instr");
		then(instr.instructions())
			.as("The operational modes service completed the instruction")
			.extracting(InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple("Completed",
					handler(InstructionHandlerInfo.Type.OperationalModes, null)))
			;
		then(instr.opModes())
			.as("The Instructor's step disabled the mode")
			.isEqualTo(new OpModeChange(Set.of(), Set.of(MODE)))
			;
		then(step(result.datum().get(2), "boost").status())
			.as("The filter is skipped once the mode is disabled")
			.isEqualTo(FilterStep.Status.Skipped)
			;
		then(result.finalState().opModes())
			.as("No modes are active at the end")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void enableOperationalModes_noMode() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"),
						instructor("EnableOperationalModes", "Expiration", "1759276920000")),
				List.of(datum("/trigger", "00:00:00")));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		FilterStep instr = step(result.datum().getFirst(), "instr");
		then(instr.instructions())
			.as("The operational modes service declines an instruction without an OpMode")
			.extracting(InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple("Declined",
					handler(InstructionHandlerInfo.Type.OperationalModes, null)))
			;
		then(instr.opModes())
			.as("No modes changed")
			.isNull()
			;
		// @formatter:on
	}

	@Test
	public void deferredEnableOperationalModes() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr", "boost"),
						instructor("EnableOperationalModes", "OpMode", MODE, "executionDate",
								"2025-10-01T00:01:00Z"),
						boosted()),
				List.of(datum("/trigger", "00:00:00"), datum("/meter/1", "00:00:30"),
						datum("/meter/1", "00:01:30")),
				null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().get(1), "boost").status())
			.as("The filter is skipped before the job runs the instruction")
			.isEqualTo(FilterStep.Status.Skipped)
			;
		then(result.instructionRuns())
			.as("The job ran the instruction, which enabled the mode")
			.extracting(InstructionRun::date, r -> r.instruction().state(),
					r -> r.instruction().handler(), InstructionRun::opModes)
			.containsExactly(tuple(at("00:01:00"), "Completed",
					handler(InstructionHandlerInfo.Type.OperationalModes, null),
					new OpModeChange(Set.of(MODE), Set.of())))
			;
		then(step(result.datum().get(2), "boost").status())
			.as("The filter applies after the job ran the instruction")
			.isEqualTo(FilterStep.Status.Changed)
			;
		// @formatter:on
	}

	@Test
	public void signal_nodeMetadataService() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"), instructor("Signal",
						SimMetadataService.NODE_METADATA_SIGNAL_NAME, "clear-cache")),
				List.of(datum("/trigger", "00:00:00")));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The Node Metadata Service completed its clear-cache signal")
			.extracting(InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple("Completed", handler(InstructionHandlerInfo.Type.Metadata,
					SimMetadataService.NODE_METADATA_SERVICE_UID)))
			;
		// @formatter:on
	}

	@Test
	public void signal_userMetadataService_unsupported() {
		// GIVEN
		SimulationEnvironment env = new SimulationEnvironment(null, null, null,
				List.of(new UserMetadataConfig("Tariffs", JSON.readTree("{}"))), null, null, null, null);
		SimulationRequest req = request(
				List.of(chain("instr"), instructor("Signal", "Tariffs", "reload")),
				List.of(datum("/trigger", "00:00:00")), env, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The User Metadata Service declined a signal it does not support")
			.extracting(InstructionInfo::state, InstructionInfo::code, InstructionInfo::handler)
			.containsExactly(tuple("Declined", SimMetadataService.USER_SIGNAL_ERROR_CODE,
					handler(InstructionHandlerInfo.Type.Metadata, "Tariffs")))
			;
		// @formatter:on
	}

	@Test
	public void signal_notHandled() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("instr"), instructor("Signal", "Other", "go")),
				List.of(datum("/trigger", "00:00:00")));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("A signal no service has the parameter of is not handled")
			.extracting(InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple(null, null))
			;
		// @formatter:on
	}

	@Test
	public void setControlParameter_handlerIsControl() {
		// GIVEN
		SimulationEnvironment env = new SimulationEnvironment(null, null, null, null, null, null, null,
				List.of(new NodeControlConfig("/ctl/1", "Integer", "0", ControlPublishMode.Never, null),
						new NodeControlConfig("/ctl/2", "Integer", "0", ControlPublishMode.Never,
								null)));
		SimulationRequest req = request(
				List.of(chain("instr"), instructor("SetControlParameter", "/ctl/2", "5")),
				List.of(datum("/trigger", "00:00:00")), env, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The handler is the control that took the instruction")
			.extracting(InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple("Completed", handler(InstructionHandlerInfo.Type.Control, "/ctl/2")))
			;
		// @formatter:on
	}

	@Test
	public void cancelInstruction_handlerIsReactor() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"), instructor("CancelInstruction", "id", "99")),
				List.of(datum("/trigger", "00:00:00")), null, runAfter(Duration.ZERO));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The reactor handles CancelInstruction, declined as no instruction has the ID")
			.extracting(InstructionInfo::state, InstructionInfo::code, InstructionInfo::handler)
			.containsExactly(tuple("Declined", "SRS.0003",
					handler(InstructionHandlerInfo.Type.Reactor, null)))
			;
		// @formatter:on
	}

}
