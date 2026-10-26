/* ==================================================================
 * ControlTests.java - 25 Oct 2026 11:40:12 am
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

import static org.assertj.core.api.BDDAssertions.entry;
import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.groups.Tuple.tuple;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.ControlInfo;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.ControlUpdate;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.InstructionInfo;
import s10k.node.sim.filter.domain.NodeControlConfig;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for simulated controls, which the Control Updater and Instructor
 * filters set.
 *
 * @author matt
 * @version 1.0
 */
public class ControlTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private static final String CONTROL_ID = "/ctl/1";

	private SimulationEngine engine;

	@BeforeEach
	public void setup() {
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	private static JsonNode meter(int minute, Object watts) {
		return JSON.readTree("""
				{"created":"2025-10-01 00:%02d:00Z","sourceId":"/meter/1","i":{"watts":%s}}"""
				.formatted(minute, watts));
	}

	private static FilterConfig chain(String... memberIds) {
		return new FilterConfig("main", StandardFilterTypes.CHAIN, null, Map.of("uid", "Chain - Main"),
				List.of(memberIds));
	}

	private static FilterConfig controlUpdater(String sourceId, String controlId, String expression) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Control Updater");
		s.put("sourceId", sourceId);
		s.put("controlConfigsCount", "1");
		s.put("controlConfigs[0].controlId", controlId);
		s.put("controlConfigs[0].expressionServiceId", SPEL);
		s.put("controlConfigs[0].expression", expression);
		s.put("controlConfigs[0].name", "ctl");
		s.put("controlConfigs[0].datumPropertyTypeKey", "s");
		return new FilterConfig("ctl", StandardFilterTypes.CONTROL_UPDATER, null, s, null);
	}

	private static FilterConfig instructor(String... controlIdExpressionPairs) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Instructor");
		s.put("sourceId", "^/meter/1$");
		s.put("instructorConfigsCount", "1");
		s.put("instructorConfigs[0].predicate.expressionServiceId", SPEL);
		s.put("instructorConfigs[0].predicate.expression", "true");
		s.put("instructorConfigs[0].instructionsCount", "1");
		s.put("instructorConfigs[0].instructions[0].topic", "SetControlParameter");
		s.put("instructorConfigs[0].instructions[0].parametersCount",
				String.valueOf(controlIdExpressionPairs.length / 2));
		for ( int i = 0; i < controlIdExpressionPairs.length; i += 2 ) {
			String prefix = "instructorConfigs[0].instructions[0].parameters[" + (i / 2) + "].";
			s.put(prefix + "name", controlIdExpressionPairs[i]);
			s.put(prefix + "expressionServiceId", SPEL);
			s.put(prefix + "expression", controlIdExpressionPairs[i + 1]);
		}
		return new FilterConfig("instr", StandardFilterTypes.INSTRUCTOR, null, s, null);
	}

	private static NodeControlConfig control(String controlId, String type, String value,
			ControlPublishMode publish) {
		return new NodeControlConfig(controlId, type, value, publish, null);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			List<NodeControlConfig> controls, SimulationOptions options) {
		return new SimulationRequest(new SimConfig(filters, "main"), null, datum, null,
				new SimulationEnvironment(null, null, null, null, null, null, null, controls), options);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			NodeControlConfig... controls) {
		return request(filters, datum, List.of(controls), null);
	}

	private static List<String> codes(SimulationResult result) {
		return result.messages().stream().map(SimulationMessage::code).toList();
	}

	private static FilterStep step(DatumTrace trace, String filterId) {
		return trace.steps().stream().filter(s -> s.filterId().equals(filterId)).findFirst()
				.orElseThrow();
	}

	@Test
	public void controlUpdater_setAndPublish() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("ctl"), controlUpdater("^/meter/1$", CONTROL_ID, "watts")),
				List.of(meter(0, 100), meter(1, 100)),
				control(CONTROL_ID, "Float", "0", ControlPublishMode.Update));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum())
			.as("Each meter datum is followed by the control datum it published, as On update publishes every set")
			.extracting(t -> t.input().sourceId())
			.containsExactly("/meter/1", CONTROL_ID, "/meter/1", CONTROL_ID)
			;
		DatumTrace first = result.datum().getFirst();
		then(step(first, "ctl").controls())
			.as("The Control Updater step set the control")
			.containsExactly(new ControlUpdate(CONTROL_ID, "Float", "0", "100", true))
			;
		then(step(first, "ctl").instructions())
			.as("The instruction completed")
			.extracting(InstructionInfo::topic, InstructionInfo::state)
			.containsExactly(tuple("SetControlParameter", "Completed"))
			;
		then(step(first, "ctl").generated())
			.as("The control datum is generated by the step")
			.containsExactly(2)
			;
		then(first.output().samples().s())
			.as("The datum property is set, as the instruction completed")
			.containsEntry("ctl", 100)
			;
		DatumTrace published = result.datum().get(1);
		then(published.origin())
			.as("The control datum is generated by the Control Updater, published by the control")
			.returns(DatumOrigin.Type.Generated, from(DatumOrigin::type))
			.returns("ctl", from(DatumOrigin::filterId))
			.returns(CONTROL_ID, from(DatumOrigin::controlId))
			.returns(1, from(DatumOrigin::parentSeq))
			;
		then(published.persist())
			.as("Not persisted by default, like the Control Datum Source's Poll mode")
			.isFalse()
			;
		then(published.input().timestamp())
			.as("Dated by the simulated clock, at the meter datum's time")
			.isEqualTo(first.input().timestamp())
			;
		then(published.input().samples().s())
			.as("Control datum status value")
			.containsEntry("val", 100)
			;
		then(published.input().samples().i())
			.as("Control datum instantaneous value, for a number control")
			.containsEntry("v", 100)
			;
		then(step(result.datum().get(2), "ctl").controls())
			.as("Setting the same value again publishes, with On update")
			.containsExactly(new ControlUpdate(CONTROL_ID, "Float", "100", "100", true))
			;
		then(result.finalState().controls())
			.as("The final state has the control value, set twice")
			.extracting(ControlInfo::controlId, ControlInfo::type, ControlInfo::value,
					ControlInfo::initialValue, ControlInfo::updates)
			.containsExactly(tuple(CONTROL_ID, "Float", "100", "0", 2))
			;
		then(result.finalState().controls().getFirst().modified())
			.as("Last set at the second meter datum's time")
			.isEqualTo(result.datum().get(2).input().timestamp())
			;
		// @formatter:on
	}

	@Test
	public void publishOnChange() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("ctl"), controlUpdater("^/meter/1$", CONTROL_ID, "watts")),
				List.of(meter(0, 100), meter(1, 100)),
				new NodeControlConfig(CONTROL_ID, "Float", "0", ControlPublishMode.Change, true));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Only the change publishes a datum")
			.extracting(t -> t.input().sourceId())
			.containsExactly("/meter/1", CONTROL_ID, "/meter/1")
			;
		then(result.datum().get(1).persist())
			.as("Persisted, as the control says")
			.isTrue()
			;
		then(step(result.datum().get(2), "ctl").controls())
			.as("Setting the same value is still a set, but does not publish")
			.containsExactly(new ControlUpdate(CONTROL_ID, "Float", "100", "100", false))
			;
		// @formatter:on
	}

	@Test
	public void publishNever() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("ctl"), controlUpdater("^/meter/1$", CONTROL_ID, "watts")),
				List.of(meter(0, 100), meter(1, 200)),
				control(CONTROL_ID, "Float", null, ControlPublishMode.Never));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("No control datum")
			.extracting(t -> t.input().sourceId())
			.containsExactly("/meter/1", "/meter/1")
			;
		then(result.finalState().controls())
			.as("The control has the last value, and had none to start")
			.extracting(ControlInfo::value, ControlInfo::initialValue, ControlInfo::updates)
			.containsExactly(tuple("200", null, 2))
			;
		// @formatter:on
	}

	@Test
	public void integerControl_declinesFraction() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("ctl"), controlUpdater("^/meter/1$", CONTROL_ID, "watts")),
				List.of(meter(0, "12.5")), control(CONTROL_ID, "Integer", "1", null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("The Control Updater logs the failed set")
			.containsExactly(SimulationMessage.CODE_FILTER_LOG)
			;
		then(result.messages().getFirst())
			.as("Logged as an error")
			.returns(SimulationMessage.Level.Error, from(SimulationMessage::level))
			;
		FilterStep ctl = step(result.datum().getFirst(), "ctl");
		then(ctl.instructions())
			.as("The instruction was declined")
			.extracting(InstructionInfo::state)
			.containsExactly("Declined")
			;
		then(ctl.controls())
			.as("Nothing was set")
			.isEmpty()
			;
		then(result.datum().getFirst().output().samples().s())
			.as("The datum property is not set")
			.isNull()
			;
		then(result.finalState().controls())
			.as("The control keeps its starting value")
			.extracting(ControlInfo::value, ControlInfo::updates)
			.containsExactly(tuple("1", 0))
			;
		// @formatter:on
	}

	@Test
	public void unknownControl() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("ctl"), controlUpdater("^/meter/1$", CONTROL_ID, "watts")),
				List.of(meter(0, 100)));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("The check reports the missing control, and the Control Updater logs the failed set")
			.containsExactly(SimulationMessage.CODE_FILTER_PROBLEM, SimulationMessage.CODE_FILTER_LOG)
			;
		then(result.messages().getFirst().message())
			.as("The problem names the control")
			.contains(CONTROL_ID)
			;
		then(step(result.datum().getFirst(), "ctl").instructions())
			.as("The instruction was not handled, like on a node without the control")
			.extracting(InstructionInfo::state)
			.containsExactly((String) null)
			;
		// @formatter:on
	}

	@Test
	public void unknownControl_placeholderNotChecked() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("ctl"), controlUpdater("^/meter/1$", "{sourceId}/ctl", "watts")),
				List.of(meter(0, 100)), control("/meter/1/ctl", "Float", null, null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("A control ID with a placeholder is not checked, and resolves to the control")
			.isEmpty()
			;
		then(result.finalState().controls())
			.as("The resolved control was set")
			.extracting(ControlInfo::value)
			.containsExactly("100")
			;
		// @formatter:on
	}

	@Test
	public void selfLoop_onChangeSettles() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("ctl"), controlUpdater(".*", CONTROL_ID, "1")),
				List.of(meter(0, 100)), control(CONTROL_ID, "Integer", "0", ControlPublishMode.Change));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum())
			.as("The control datum sets the same value again, which does not publish")
			.extracting(t -> t.input().sourceId())
			.containsExactly("/meter/1", CONTROL_ID)
			;
		// @formatter:on
	}

	@Test
	public void selfLoop_onUpdateStopped() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("ctl"), controlUpdater(".*", CONTROL_ID, "1")),
				List.of(meter(0, 100)), control(CONTROL_ID, "Integer", "0", ControlPublishMode.Update));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("Every set publishes, so the loop runs until the generation depth limit")
			.containsExactly(SimulationMessage.CODE_GENERATION_DEPTH_LIMIT)
			;
		then(result.stats())
			.as("The meter datum, then a control datum for each generation")
			.returns(SimulationOptions.DEFAULT_MAX_GENERATION_DEPTH + 1, from(s -> s.processed()))
			;
		// @formatter:on
	}

	@Test
	public void instructor_firstControlOnly() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("instr"), instructor("/ctl/2", "2", CONTROL_ID, "1")),
				List.of(meter(0, 100)), control(CONTROL_ID, "Integer", "0", ControlPublishMode.Never),
				control("/ctl/2", "Integer", "0", ControlPublishMode.Never));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(step(result.datum().getFirst(), "instr").controls())
			.as("Like a node asking each control in turn, only the first control the instruction names changes")
			.containsExactly(new ControlUpdate(CONTROL_ID, "Integer", "0", "1", false))
			;
		then(result.finalState().controls())
			.extracting(ControlInfo::controlId, ControlInfo::value)
			.as("The second control is not set")
			.containsExactly(tuple(CONTROL_ID, "1"), tuple("/ctl/2", "0"))
			;
		// @formatter:on
	}

	@Test
	public void debugSnapshot() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("ctl"), controlUpdater("^/meter/1$", CONTROL_ID, "watts")),
				List.of(meter(0, 100), meter(1, 200)),
				List.of(control(CONTROL_ID, "Float", "0", ControlPublishMode.Never)),
				new SimulationOptions(null, null, null, SimulationOptions.Detail.Debug, null, null, null,
						null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Each datum's starting state has the control value before it")
			.extracting(t -> t.startState().controls().getFirst().value())
			.containsExactly("0", "100")
			;
		// @formatter:on
	}

	@Test
	public void invalidControls() {
		// GIVEN
		SimulationRequest req = request(List.of(chain()), List.of(meter(0, 100)),
				control(" ", "Float", "0", null), control(CONTROL_ID, "Integer", "1.5", null),
				control(CONTROL_ID, "Integer", "1", null), control("/ctl/2", "Nope", "yes", null),
				control("/ctl/3", null, null, null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("No ID, an invalid starting value, a repeated ID, and an unknown type")
			.containsExactly(SimulationMessage.CODE_INVALID_CONTROL,
					SimulationMessage.CODE_INVALID_CONTROL,
					SimulationMessage.CODE_DUPLICATE_CONTROL_ID,
					SimulationMessage.CODE_INVALID_CONTROL)
			;
		then(result.finalState().controls())
			.as("The first of a repeated ID is kept, without a value; types default to Boolean")
			.extracting(ControlInfo::controlId, ControlInfo::type, ControlInfo::value)
			.containsExactly(
					tuple(CONTROL_ID, "Integer", null),
					tuple("/ctl/2", "Boolean", "true"),
					tuple("/ctl/3", "Boolean", null))
			;
		// @formatter:on
	}

	@Test
	public void propertyNameAndType() {
		// GIVEN
		Map<String, String> u = new LinkedHashMap<>();
		u.put("uid", "Control Updater");
		u.put("sourceId", "^/meter/1$");
		u.put("controlConfigsCount", "2");
		u.put("controlConfigs[0].controlId", CONTROL_ID);
		u.put("controlConfigs[0].expressionServiceId", SPEL);
		u.put("controlConfigs[0].expression", "watts");
		u.put("controlConfigs[1].controlId", "/ctl/2");
		u.put("controlConfigs[1].expressionServiceId", SPEL);
		u.put("controlConfigs[1].expression", "watts > 50");
		FilterConfig updater = new FilterConfig("ctl", StandardFilterTypes.CONTROL_UPDATER, null, u,
				null);
		Map<String, String> x = new LinkedHashMap<>();
		x.put("uid", "Expression");
		x.put("sourceId", "^/ctl/");
		x.put("expressionConfigsCount", "1");
		x.put("expressionConfigs[0].name", "doubled");
		x.put("expressionConfigs[0].datumPropertyTypeKey", "i");
		x.put("expressionConfigs[0].expressionServiceId", SPEL);
		x.put("expressionConfigs[0].expression", "has('limit') ? limit * 2 : relay * 10");
		FilterConfig expr = new FilterConfig("expr", StandardFilterTypes.EXPRESSION, null, x, null);
		SimulationRequest req = request(List.of(chain("ctl", "expr"), updater, expr),
				List.of(meter(0, 100)),
				new NodeControlConfig(CONTROL_ID, "Float", "0", ControlPublishMode.Update, null, "limit",
						null),
				new NodeControlConfig("/ctl/2", "Boolean", "false", ControlPublishMode.Update, null,
						"relay", "i"),
				new NodeControlConfig("/ctl/3", "Float", null, ControlPublishMode.Never, null, null,
						"Tag"));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("The property type that is not Instantaneous, Accumulating, or Status is reported")
			.extracting(SimulationMessage::code, SimulationMessage::message)
			.containsExactly(tuple(SimulationMessage.CODE_INVALID_CONTROL,
					"The control /ctl/3 has the Property Type \"Tag\", which is not one of Instantaneous, Accumulating, or Status, so its value is a Status property."))
			;
		then(result.datum())
			.as("Each control published a datum")
			.extracting(t -> t.input().sourceId())
			.containsExactly("/meter/1", CONTROL_ID, "/ctl/2")
			;
		DatumTrace limit = result.datum().get(1);
		then(limit.input().samples().s())
			.as("The value is a status property with the control's property name")
			.containsExactly(entry("limit", 100))
			;
		then(limit.input().samples().i())
			.as("No v property, as the property name is not the default, like SolarNode")
			.isNullOrEmpty()
			;
		then(limit.output().samples().i())
			.as("Expressions read the property by its name")
			.containsEntry("doubled", 200)
			;
		DatumTrace relay = result.datum().get(2);
		then(relay.input().samples().s())
			.as("The status property moved to the property type")
			.isNullOrEmpty()
			;
		then(relay.input().samples().i())
			.as("The Boolean value is an instantaneous 1, like a GPIO control")
			.containsExactly(entry("relay", 1))
			;
		then(relay.output().samples().i())
			.as("Expressions read the property by its name")
			.containsEntry("doubled", 10)
			;
		// @formatter:on
	}

}
