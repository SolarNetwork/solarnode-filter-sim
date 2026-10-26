/* ==================================================================
 * ConductorTests.java - 25 Oct 2026 12:10:31 pm
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
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.ControlConductorConfig;
import s10k.node.sim.filter.domain.ControlInfo;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.ControlUpdate;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.InstructionInfo;
import s10k.node.sim.filter.domain.InstructionRun;
import s10k.node.sim.filter.domain.NodeControlConfig;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimInstructionService;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for simulated Control Conductors, and the simulated Instruction
 * Execution Job that runs their tasks.
 *
 * @author matt
 * @version 1.0
 */
public class ConductorTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private static final String CONTROL_ID = "/hvac/1/mode";

	private static final String CONDUCTOR = "HVAC DR";

	private SimulationEngine engine;

	/** A Control Conductor task. */
	private record Task(String controlId, String offset, String value, boolean expression) {

	}

	@BeforeEach
	public void setup() {
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	private static JsonNode meter(String time) {
		return datum("/meter/1", time);
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
	 * An Instructor that issues one instruction for each datum, with plain
	 * parameters.
	 */
	private static FilterConfig instructor(String topic, String... nameValuePairs) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Instructor");
		s.put("sourceId", "^/meter/1$");
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

	private static ControlConductorConfig conductor(String uid, String predicate, Task... tasks) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", uid);
		if ( predicate != null ) {
			s.put("predicate.expressionServiceId", SPEL);
			s.put("predicate.expression", predicate);
		}
		s.put("taskConfigsCount", String.valueOf(tasks.length));
		for ( int i = 0; i < tasks.length; i++ ) {
			String prefix = "taskConfigs[" + i + "].";
			s.put(prefix + "controlId", tasks[i].controlId());
			s.put(prefix + "executionOffset", tasks[i].offset());
			s.put(prefix + "value", tasks[i].value());
			if ( tasks[i].expression() ) {
				s.put(prefix + "expressionServiceId", SPEL);
			}
		}
		return new ControlConductorConfig(s);
	}

	private static NodeControlConfig control() {
		return new NodeControlConfig(CONTROL_ID, "Integer", "0", ControlPublishMode.Change, null);
	}

	private static SimulationOptions runAfter(Duration after, Integer jobSeconds) {
		return new SimulationOptions(null, null, null, null, null, null, after, null, jobSeconds);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			List<ControlConductorConfig> conductors, SimulationOptions options) {
		return new SimulationRequest(new SimConfig(filters, "main"), null, datum, null,
				new SimulationEnvironment(null, null, null, null, null, null, null, List.of(control()),
						conductors),
				options);
	}

	/** The handbook's HVAC demand response example. */
	private static ControlConductorConfig hvac() {
		return conductor(CONDUCTOR, null,
				new Task(CONTROL_ID, "-PT1M", "mode == 'Cool' ? 1 : mode == 'Heat' ? 3 : 2", true),
				new Task(CONTROL_ID, "0", "4", false), new Task(CONTROL_ID, "{duration}", "0", false));
	}

	private static FilterConfig orchestrate(String... extraNameValuePairs) {
		String[] params = new String[4 + extraNameValuePairs.length];
		params[0] = "service";
		params[1] = CONDUCTOR;
		params[2] = "mode";
		params[3] = "Heat";
		System.arraycopy(extraNameValuePairs, 0, params, 4, extraNameValuePairs.length);
		return instructor("OrchestrateControls", params);
	}

	private static List<String> codes(SimulationResult result) {
		return result.messages().stream().map(SimulationMessage::code).toList();
	}

	private static FilterStep step(DatumTrace trace, String filterId) {
		return trace.steps().stream().filter(s -> s.filterId().equals(filterId)).findFirst()
				.orElseThrow();
	}

	@Test
	public void handbookExample() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"), orchestrate("date", "2025-10-01T00:10:00Z", "duration", "PT1M")),
				List.of(meter("00:05:00")), List.of(hvac()), runAfter(Duration.ofMinutes(10), null));

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
			.as("The OrchestrateControls instruction stored a Signal for each task, due later")
			.extracting(InstructionInfo::number, InstructionInfo::topic, InstructionInfo::state,
					InstructionInfo::parent, InstructionInfo::executeAt, InstructionInfo::conductor,
					InstructionInfo::task)
			.containsExactly(
					tuple(1, "OrchestrateControls", "Completed", null, null, null, null),
					tuple(2, "Signal", "Received", 1, at("00:09:00"), CONDUCTOR, 1),
					tuple(3, "Signal", "Received", 1, at("00:10:00"), CONDUCTOR, 2),
					tuple(4, "Signal", "Received", 1, at("00:11:00"), CONDUCTOR, 3))
			;
		then(instr.instructions().getFirst().message())
			.as("The conductor's result message")
			.isEqualTo("Scheduled 3 control tasks.")
			;
		then(instr.controls())
			.as("No control was set while the Instructor ran")
			.isEmpty()
			;
		then(result.instructionRuns())
			.as("The job ran each task at its time")
			.extracting(InstructionRun::date, r -> r.instruction().number(), r -> r.instruction().state())
			.containsExactly(
					tuple(at("00:09:00"), 2, "Completed"),
					tuple(at("00:10:00"), 3, "Completed"),
					tuple(at("00:11:00"), 4, "Completed"))
			;
		then(result.instructionRuns())
			.as("Each task set the control, and the control published its datum")
			.extracting(InstructionRun::controls, InstructionRun::generated)
			.containsExactly(
					tuple(List.of(new ControlUpdate(CONTROL_ID, "Integer", "0", "3", true)), List.of(2)),
					tuple(List.of(new ControlUpdate(CONTROL_ID, "Integer", "3", "4", true)), List.of(3)),
					tuple(List.of(new ControlUpdate(CONTROL_ID, "Integer", "4", "0", true)), List.of(4)))
			;
		then(result.instructionRuns().getFirst())
			.as("The run was issued by the Instructor's step, after the first datum")
			.returns(1, from(InstructionRun::datumSeq))
			.returns(instr.id(), from(InstructionRun::stepId))
			.returns("instr", from(InstructionRun::filterId))
			.returns(1, from(InstructionRun::afterSeq))
			;
		then(result.instructionRuns().getFirst().instructions())
			.as("The task issued a SetControlParameter instruction")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::parameters)
			.containsExactly(tuple("SetControlParameter", "Completed", Map.of(CONTROL_ID, "3")))
			;
		DatumTrace published = result.datum().get(1);
		then(published.origin())
			.as("The control datum was published while the job ran the first task")
			.returns(DatumOrigin.Type.Instruction, from(DatumOrigin::type))
			.returns(2, from(DatumOrigin::instruction))
			.returns("instr", from(DatumOrigin::filterId))
			.returns(CONTROL_ID, from(DatumOrigin::controlId))
			;
		then(result.datum())
			.as("The meter datum, then each control datum, at the task times")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp())
			.containsExactly(
					tuple("/meter/1", at("00:05:00")),
					tuple(CONTROL_ID, at("00:09:00")),
					tuple(CONTROL_ID, at("00:10:00")),
					tuple(CONTROL_ID, at("00:11:00")))
			;
		then(result.finalState().controls())
			.as("The control was set three times")
			.extracting(ControlInfo::value, ControlInfo::updates)
			.containsExactly(tuple("0", 3))
			;
		then(result.finalState().instructions())
			.as("No instruction is waiting")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void jobTicks() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"),
						orchestrate("date", "2025-10-01T00:10:00Z", "duration", "PT1M1S")),
				List.of(meter("00:05:00"), datum("/meter/2", "00:11:05")), List.of(hvac()),
				runAfter(Duration.ofMinutes(1), null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.instructionRuns())
			.as("A task due at 00:11:01 runs on the next 10 second tick, after the datum at 00:11:05")
			.extracting(InstructionRun::date, InstructionRun::afterSeq)
			.contains(tuple(at("00:11:10"), 4))
			;
		// @formatter:on
	}

	@Test
	public void jobTicks_configured() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"),
						orchestrate("date", "2025-10-01T00:10:00Z", "duration", "PT1M1S")),
				List.of(meter("00:05:00")), List.of(hvac()), runAfter(Duration.ofMinutes(10), 7));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.instructionRuns())
			.as("The job runs every 7 seconds from the minute")
			.extracting(InstructionRun::date)
			.containsExactly(at("00:09:00"), at("00:10:00"), at("00:11:07"))
			;
		// @formatter:on
	}

	@Test
	public void jobTick() {
		// @formatter:off
		then(SimInstructionService.jobTick(at("00:00:00"), 10))
			.as("On a tick")
			.isEqualTo(at("00:00:00"))
			;
		then(SimInstructionService.jobTick(Instant.parse("2025-10-01T00:00:00.001Z"), 10))
			.as("Just after a tick")
			.isEqualTo(at("00:00:10"))
			;
		then(SimInstructionService.jobTick(at("00:00:51"), 10))
			.as("To the next minute")
			.isEqualTo(at("00:01:00"))
			;
		then(SimInstructionService.jobTick(at("00:00:57"), 7))
			.as("Like cron, the last tick of the minute is 56, then the next minute")
			.isEqualTo(at("00:01:00"))
			;
		then(SimInstructionService.jobTick(at("00:00:01"), 90))
			.as("More than a minute is on the minute")
			.isEqualTo(at("00:01:00"))
			;
		// @formatter:on
	}

	@Test
	public void tasksDueNow_runInInstructorStep() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("instr"), orchestrate()),
				List.of(meter("00:05:00")),
				List.of(conductor(CONDUCTOR, null, new Task(CONTROL_ID, "0", "4", false))), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		FilterStep instr = step(result.datum().getFirst(), "instr");
		then(instr.controls())
			.as("Without a date, the orchestration date is now, so the task ran at once")
			.containsExactly(new ControlUpdate(CONTROL_ID, "Integer", "0", "4", true))
			;
		then(instr.instructions())
			.as("The Signal completed at once, as did its SetControlParameter")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::parent)
			.containsExactlyInAnyOrder(
					tuple("OrchestrateControls", "Completed", null),
					tuple("SetControlParameter", "Completed", 1),
					tuple("Signal", "Completed", 1))
			;
		then(instr.generated())
			.as("The control datum is generated by the Instructor's step")
			.containsExactly(2)
			;
		then(result.instructionRuns())
			.as("Nothing was left for the job")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void predicateDeclines() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("instr"), orchestrate()),
				List.of(meter("00:05:00")),
				List.of(conductor(CONDUCTOR, "mode == 'Cool'", new Task(CONTROL_ID, "0", "4", false))),
				null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The predicate is not true for the Heat mode, so the conductor declined")
			.extracting(InstructionInfo::state, InstructionInfo::code)
			.containsExactly(tuple("Declined", "CC.0011"))
			;
		then(result.finalState().controls())
			.as("The control was not set")
			.extracting(ControlInfo::updates)
			.containsExactly(0)
			;
		// @formatter:on
	}

	@Test
	public void taskWithoutValue_declines() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("instr"), orchestrate()),
				List.of(meter("00:05:00")),
				List.of(conductor(CONDUCTOR, null, new Task(CONTROL_ID, "0", "4", false),
						new Task(CONTROL_ID, "PT1M", "", false))),
				runAfter(Duration.ofMinutes(5), null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("The task is reported")
			.containsExactly(SimulationMessage.CODE_INVALID_CONDUCTOR)
			;
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The conductor declined the whole orchestration")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::code)
			.containsExactly(tuple("OrchestrateControls", "Declined", "CC.0002"))
			;
		then(result.instructionRuns())
			.as("Nothing was scheduled")
			.isEmpty()
			;
		// @formatter:on
	}

	@Test
	public void unknownConductor() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("instr"), orchestrate()),
				List.of(meter("00:05:00")),
				List.of(conductor("Other", null, new Task(CONTROL_ID, "0", "4", false))), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("The Instructor check reports the missing conductor, and the Instructor logs that it was not handled")
			.containsExactly(SimulationMessage.CODE_FILTER_PROBLEM, SimulationMessage.CODE_FILTER_LOG)
			;
		then(result.messages().getFirst().message())
			.as("The problem names the conductor")
			.contains("\"HVAC DR\"")
			;
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("No conductor handled the instruction, like on a node")
			.extracting(InstructionInfo::state)
			.containsExactly((String) null)
			;
		// @formatter:on
	}

	@Test
	public void pendingTasks() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"), orchestrate("date", "2025-10-01T00:10:00Z", "duration", "PT1M")),
				List.of(meter("00:05:00")), List.of(hvac()), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("The tasks due after the end are reported")
			.containsExactly(SimulationMessage.CODE_PENDING_INSTRUCTIONS)
			;
		then(result.messages().getFirst().message())
			.as("The message names the conductor and the first task's time")
			.contains("\"HVAC DR\" has 3 tasks", "2025-10-01T00:09:00Z")
			;
		then(result.finalState().instructions())
			.as("The final state lists the waiting tasks")
			.extracting(InstructionInfo::task, InstructionInfo::executeAt)
			.containsExactly(
					tuple(1, at("00:09:00")),
					tuple(2, at("00:10:00")),
					tuple(3, at("00:11:00")))
			;
		// @formatter:on
	}

	@Test
	public void deferredInstructorInstruction() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"),
						instructor("SetControlParameter", CONTROL_ID, "5", "executionDate",
								"2025-10-01T00:06:00Z")),
				List.of(meter("00:05:00")), List.of(), runAfter(Duration.ofMinutes(5), null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The deferred instruction was stored as Received, like on a node")
			.extracting(InstructionInfo::state, InstructionInfo::executeAt)
			.containsExactly(tuple("Received", at("00:06:00")))
			;
		then(result.instructionRuns())
			.as("The job ran it at its execution date")
			.extracting(InstructionRun::date, r -> r.instruction().state(), InstructionRun::controls)
			.containsExactly(tuple(at("00:06:00"), "Completed",
					List.of(new ControlUpdate(CONTROL_ID, "Integer", "0", "5", true))))
			;
		// @formatter:on
	}

	@Test
	public void instructorInstruction_recordedOnce() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"), instructor("SetControlParameter", CONTROL_ID, "5")),
				List.of(meter("00:05:00")), List.of(), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The Instructor stores, executes, and stores the instruction again, but it is one instruction")
			.extracting(InstructionInfo::number, InstructionInfo::state, InstructionInfo::executeAt)
			.containsExactly(tuple(1, "Completed", null))
			;
		then(result.instructionRuns())
			.as("The job did not run it again")
			.isEmpty()
			;
		// @formatter:on
	}

	/**
	 * The handbook's DR Instructor: cancel the last DR event's orchestration,
	 * then orchestrate a new one in two minutes, saving its instruction ID.
	 * With {@code force}, the cancel also cancels the last orchestration's
	 * tasks.
	 */
	private static FilterConfig drInstructor(boolean force) {
		final String cancel = "instructorConfigs[0].instructions[0].";
		final String orchestrate = "instructorConfigs[0].instructions[1].";
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Execute DR Event");
		s.put("sourceId", "^/dr/event$");
		s.put("instructorConfigsCount", "1");
		s.put("instructorConfigs[0].predicate.expressionServiceId", SPEL);
		s.put("instructorConfigs[0].predicate.expression", "true");
		s.put("instructorConfigs[0].instructionsCount", "2");
		s.put(cancel + "topic", "CancelInstruction");
		s.put(cancel + "parametersCount", force ? "3" : "2");
		s.put(cancel + "parameters[0].name", "id");
		s.put(cancel + "parameters[0].expressionServiceId", SPEL);
		s.put(cancel + "parameters[0].expression", "localState('dr-instruction-id')");
		s.put(cancel + "parameters[1].name", "ignoreErrors");
		s.put(cancel + "parameters[1].expression", "true");
		if ( force ) {
			s.put(cancel + "parameters[2].name", "force");
			s.put(cancel + "parameters[2].expression", "true");
		}
		s.put(orchestrate + "topic", "OrchestrateControls");
		s.put(orchestrate + "parametersCount", "2");
		s.put(orchestrate + "parameters[0].name", "service");
		s.put(orchestrate + "parameters[0].expression", CONDUCTOR);
		s.put(orchestrate + "parameters[1].name", "date");
		s.put(orchestrate + "parameters[1].expressionServiceId", SPEL);
		s.put(orchestrate + "parameters[1].expression", "datePlus(timestamp(), 'PT2M')");
		s.put(orchestrate + "responsesCount", "1");
		s.put(orchestrate + "responses[0].name", "instructionId");
		s.put(orchestrate + "responses[0].datumPropertyTypeKey", "s");
		s.put(orchestrate + "responses[0].expressionServiceId", SPEL);
		s.put(orchestrate + "responses[0].expression", """
				has('instructionResult') && instructionResult.completed
				? saveLocalState('dr-instruction-id', instructionResult.instructionId)
				: null""");
		return new FilterConfig("instr", StandardFilterTypes.INSTRUCTOR, null, s, null);
	}

	private static SimulationRequest drRequest(boolean force) {
		return request(List.of(chain("instr"), drInstructor(force)),
				List.of(datum("/dr/event", "00:05:00"), datum("/dr/event", "00:06:00")),
				List.of(conductor(CONDUCTOR, null, new Task(CONTROL_ID, "0", "4", false),
						new Task(CONTROL_ID, "PT1M", "0", false))),
				runAfter(Duration.ofMinutes(5), null));
	}

	@Test
	public void cancelOrchestration_force() {
		// GIVEN
		SimulationRequest req = drRequest(true);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("No DR event to cancel yet, which ignoreErrors makes Completed")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::code)
			.startsWith(tuple("CancelInstruction", "Completed", "SRS.0001"))
			;
		then(step(result.datum().get(1), "instr").instructions())
			.as("The completed orchestration cannot be cancelled, but force cancels its tasks")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::task,
					InstructionInfo::code)
			.contains(
					tuple("CancelInstruction", "Completed", null, "SRS.0004"),
					tuple("Signal", "Declined", 1, null),
					tuple("Signal", "Declined", 2, null))
			;
		then(result.instructionRuns())
			.as("Only the second DR event's tasks ran")
			.extracting(InstructionRun::date, r -> r.instruction().task())
			.containsExactly(
					tuple(at("00:08:00"), 1),
					tuple(at("00:09:00"), 2))
			;
		// @formatter:on
	}

	@Test
	public void cancelOrchestration_handbook() {
		// GIVEN
		SimulationRequest req = drRequest(false);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().get(1), "instr").instructions())
			.as("Without force, the completed orchestration's tasks are not cancelled")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::message)
			.startsWith(tuple("CancelInstruction", "Completed", "Instruction failed to update state."))
			;
		then(result.instructionRuns())
			.as("Both DR events' tasks ran")
			.extracting(InstructionRun::date)
			.containsExactly(at("00:07:00"), at("00:08:00"), at("00:08:00"), at("00:09:00"))
			;
		// @formatter:on
	}

	@Test
	public void cancelDeferredInstruction() {
		// GIVEN
		final String instr = "instructorConfigs[0].instructions[";
		Map<String, String> s = new LinkedHashMap<>(instructor("SetControlParameter", CONTROL_ID, "5",
				"executionDate", "2025-10-01T00:06:00Z").settings());
		s.put(instr + "0].responsesCount", "1");
		s.put(instr + "0].responses[0].expressionServiceId", SPEL);
		s.put(instr + "0].responses[0].expression",
				"saveLocalState('id', instructionResult.instructionId)");
		s.put("instructorConfigs[0].instructionsCount", "2");
		s.put(instr + "1].topic", "CancelInstruction");
		s.put(instr + "1].parametersCount", "1");
		s.put(instr + "1].parameters[0].name", "id");
		s.put(instr + "1].parameters[0].expressionServiceId", SPEL);
		s.put(instr + "1].parameters[0].expression", "localState('id')");
		SimulationRequest req = request(
				List.of(chain("instr"),
						new FilterConfig("instr", StandardFilterTypes.INSTRUCTOR, null, s, null)),
				List.of(meter("00:05:00")), List.of(), runAfter(Duration.ofMinutes(5), null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(step(result.datum().getFirst(), "instr").instructions())
			.as("The deferred instruction was cancelled")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::executeAt)
			.containsExactly(
					tuple("SetControlParameter", "Declined", null),
					tuple("CancelInstruction", "Completed", null))
			;
		then(step(result.datum().getFirst(), "instr").instructions().getFirst().message())
			.as("The cancelled instruction says what cancelled it")
			.startsWith("Instruction cancelled by CancelInstruction Instruction ")
			;
		then(result.instructionRuns())
			.as("The job never ran it")
			.isEmpty()
			;
		then(result.finalState().controls())
			.as("The control was not set")
			.extracting(ControlInfo::value)
			.containsExactly("0")
			;
		// @formatter:on
	}

}
