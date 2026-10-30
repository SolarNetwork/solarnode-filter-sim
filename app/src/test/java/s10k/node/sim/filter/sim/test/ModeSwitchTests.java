/* ==================================================================
 * ModeSwitchTests.java - 29 Oct 2026 5:10:00 pm
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
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.ControlInfo;
import s10k.node.sim.filter.domain.ControlKind;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.ControlUpdate;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.InstructionHandlerInfo;
import s10k.node.sim.filter.domain.InstructionInfo;
import s10k.node.sim.filter.domain.NodeControlConfig;
import s10k.node.sim.filter.domain.OpModeChange;
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
 * Test cases for the Operational Mode Switch, and the control events it posts.
 *
 * @author matt
 * @version 1.0
 */
public class ModeSwitchTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private static final String MODE = "boost";

	private static final String SWITCH_ID = "/switch/boost";

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

	/**
	 * A Control Updater that sets {@link #SWITCH_ID} to whether the meter's
	 * watts are over 100.
	 */
	private static FilterConfig controlUpdater() {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Control Updater");
		s.put("sourceId", "^/meter/1$");
		s.put("controlConfigsCount", "1");
		s.put("controlConfigs[0].controlId", SWITCH_ID);
		s.put("controlConfigs[0].expressionServiceId", SPEL);
		s.put("controlConfigs[0].expression", "watts > 100");
		return new FilterConfig("ctl", StandardFilterTypes.CONTROL_UPDATER, null, s, null);
	}

	/**
	 * An Operational Mode filter that enables {@link #MODE} while the meter's
	 * watts are over 100.
	 */
	private static FilterConfig opModeFilter(int expireSeconds) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Mode");
		s.put("sourceId", "^/meter/1$");
		s.put("expressionConfigsCount", "1");
		s.put("expressionConfigs[0].operationalMode", MODE);
		s.put("expressionConfigs[0].expressionServiceId", SPEL);
		s.put("expressionConfigs[0].expression", "watts > 100");
		s.put("expressionConfigs[0].expireSeconds", String.valueOf(expireSeconds));
		return new FilterConfig("mode", StandardFilterTypes.OP_MODE, null, s, null);
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

	private static NodeControlConfig modeSwitch(String mode, ControlPublishMode publish) {
		return NodeControlConfig.modeSwitch(SWITCH_ID, mode, publish, null);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			List<String> opModes, List<NodeControlConfig> controls, SimulationOptions options) {
		return new SimulationRequest(new SimConfig(filters, "main"), null, datum, null,
				new SimulationEnvironment(opModes, null, null, null, null, null, null, controls, null),
				options);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			NodeControlConfig... controls) {
		return request(filters, datum, null, List.of(controls), null);
	}

	private static List<String> codes(SimulationResult result) {
		return result.messages().stream().map(SimulationMessage::code).toList();
	}

	private static FilterStep step(DatumTrace trace, String filterId) {
		return trace.steps().stream().filter(s -> s.filterId().equals(filterId)).findFirst()
				.orElseThrow();
	}

	private static List<String> sourceIds(SimulationResult result) {
		return result.datum().stream().map(t -> t.input().sourceId()).toList();
	}

	@Test
	public void controlUpdater_turnsSwitchOnAndOff() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("ctl", "boost"), controlUpdater(), boosted()),
				List.of(meter("00:00:00", 200), meter("00:01:00", 300), meter("00:02:00", 50)),
				// the operational modes service lowercases mode names
				modeSwitch("Boost", ControlPublishMode.Change));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(sourceIds(result))
			.as("The switch publishes a datum when its value changes, not when it is set to the same value")
			.containsExactly("/meter/1", SWITCH_ID, "/meter/1", "/meter/1", SWITCH_ID)
			;
		DatumTrace first = result.datum().getFirst();
		FilterStep ctl = step(first, "ctl");
		then(ctl.instructions())
			.as("The switch completed the instruction")
			.extracting(InstructionInfo::topic, InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple("SetControlParameter", "Completed", new InstructionHandlerInfo(
					InstructionHandlerInfo.Type.OperationalModeSwitch, SWITCH_ID)))
			;
		then(ctl.controls())
			.as("The step set the switch, which published its datum")
			.containsExactly(new ControlUpdate(SWITCH_ID, "Boolean", "false", "true", true))
			;
		then(ctl.opModes())
			.as("Setting the switch enabled its mode")
			.isEqualTo(new OpModeChange(Set.of(MODE), Set.of()))
			;
		then(ctl.generated())
			.as("The switch datum is generated by the step")
			.containsExactly(2)
			;
		then(step(first, "boost").status())
			.as("The filter that requires the mode applies after the switch is set")
			.isEqualTo(FilterStep.Status.Changed)
			;
		DatumTrace published = result.datum().get(1);
		then(published.origin())
			.as("Published by the switch, set by the Control Updater")
			.returns(DatumOrigin.Type.Generated, from(DatumOrigin::type))
			.returns("ctl", from(DatumOrigin::filterId))
			.returns(SWITCH_ID, from(DatumOrigin::controlId))
			;
		then(published.input().timestamp())
			.as("Dated by the simulated clock")
			.isEqualTo(at("00:00:00"))
			;
		then(published.persist())
			.as("Not persisted by default")
			.isFalse()
			;
		then(step(result.datum().get(2), "ctl").controls())
			.as("Setting the same value publishes nothing")
			.containsExactly(new ControlUpdate(SWITCH_ID, "Boolean", "true", "true", false))
			;
		then(step(result.datum().get(3), "ctl").controls())
			.as("Turning the switch off publishes its datum")
			.containsExactly(new ControlUpdate(SWITCH_ID, "Boolean", "true", "false", true))
			;
		then(step(result.datum().get(3), "boost").status())
			.as("The filter that requires the mode is skipped once the switch is off")
			.isEqualTo(FilterStep.Status.Skipped)
			;
		then(result.finalState().controls())
			.as("The final state has the switch, set three times")
			.extracting(ControlInfo::controlId, ControlInfo::type, ControlInfo::value,
					ControlInfo::initialValue, ControlInfo::updates, ControlInfo::kind, ControlInfo::mode)
			.containsExactly(tuple(SWITCH_ID, "Boolean", "false", "false", 3,
					ControlKind.OperationalModeSwitch, "Boost"))
			;
		then(result.finalState().controls().getFirst().modified())
			.as("Last set at the third meter datum's time")
			.isEqualTo(at("00:02:00"))
			;
		// @formatter:on
	}

	@Test
	public void opModeFilter_publishesSwitchDatum() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("mode"), opModeFilter(0)),
				List.of(meter("00:00:00", 200), meter("00:01:00", 50)),
				NodeControlConfig.modeSwitch(SWITCH_ID, MODE, ControlPublishMode.Change, true));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(sourceIds(result))
			.as("The switch publishes a datum each time the filter changes its mode")
			.containsExactly("/meter/1", SWITCH_ID, "/meter/1", SWITCH_ID)
			;
		FilterStep mode = step(result.datum().getFirst(), "mode");
		then(mode.controls())
			.as("The filter's step changed the switch's value, with its mode, without a set")
			.containsExactly(new ControlUpdate(SWITCH_ID, "Boolean", "false", "true", true, MODE))
			;
		then(mode.generated())
			.as("The switch datum is generated by the filter's step")
			.containsExactly(2)
			;
		DatumTrace published = result.datum().get(1);
		then(published.origin())
			.as("Published by the switch, after the Operational Mode filter enabled its mode")
			.returns(DatumOrigin.Type.Generated, from(DatumOrigin::type))
			.returns("mode", from(DatumOrigin::filterId))
			.returns(SWITCH_ID, from(DatumOrigin::controlId))
			;
		then(published.persist())
			.as("Persisted, as configured")
			.isTrue()
			;
		then(step(result.datum().get(2), "mode").controls())
			.as("The filter disabled the mode")
			.containsExactly(new ControlUpdate(SWITCH_ID, "Boolean", "true", "false", true, MODE))
			;
		then(result.finalState().controls())
			.as("The switch was never set")
			.extracting(ControlInfo::value, ControlInfo::updates)
			.containsExactly(tuple("false", 0))
			;
		// @formatter:on
	}

	@Test
	public void publishNever() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("mode"), opModeFilter(0)),
				List.of(meter("00:00:00", 200)), modeSwitch(MODE, ControlPublishMode.Never));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(sourceIds(result))
			.as("The switch publishes nothing")
			.containsExactly("/meter/1")
			;
		then(step(result.datum().getFirst(), "mode").controls())
			.as("The step still changed the switch's value")
			.containsExactly(new ControlUpdate(SWITCH_ID, "Boolean", "false", "true", false, MODE))
			;
		// @formatter:on
	}

	@Test
	public void expiringMode_publishedByAutoExpireTask() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("mode"), opModeFilter(30)),
				List.of(meter("00:00:00", 200), meter("00:02:00", 50)), null,
				List.of(modeSwitch(MODE, ControlPublishMode.Change)), null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages: the auto-expire task never ends, so it is not pending")
			.isEmpty()
			;
		then(sourceIds(result))
			.as("The switch publishes when the mode starts, and when the auto-expire task finds it expired")
			.containsExactly("/meter/1", SWITCH_ID, SWITCH_ID, "/meter/1")
			;
		DatumTrace expired = result.datum().get(2);
		then(expired.input().timestamp())
			.as("The auto-expire task runs every 20 seconds, so it finds the mode expired at 30 s at 40 s")
			.isEqualTo(at("00:00:40"))
			;
		then(expired.origin())
			.as("Published by the switch during a scheduled task of no filter")
			.returns(DatumOrigin.Type.Scheduled, from(DatumOrigin::type))
			.returns(null, from(DatumOrigin::filterId))
			.returns(SWITCH_ID, from(DatumOrigin::controlId))
			;
		then(expired.input().samples().s())
			.as("The switch is off")
			.containsEntry("val", 0)
			;
		// @formatter:on
	}

	@Test
	public void startingModes_notPublished() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("boost"), boosted()),
				List.of(meter("00:00:00", 200)), List.of(MODE),
				List.of(modeSwitch(MODE, ControlPublishMode.Change)), new SimulationOptions(null, null,
						null, null, null, null, Duration.ofMinutes(1), null, null));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(sourceIds(result))
			.as("The starting mode is the switch's starting value, not a change, so it publishes nothing")
			.containsExactly("/meter/1")
			;
		then(step(result.datum().getFirst(), "boost").status())
			.as("The starting mode is active")
			.isEqualTo(FilterStep.Status.Changed)
			;
		then(result.finalState().controls())
			.as("The switch starts on")
			.extracting(ControlInfo::value, ControlInfo::initialValue, ControlInfo::updates)
			.containsExactly(tuple("true", "true", 0))
			;
		then(result.finalState().opModes())
			.as("The starting mode is still active, after the service read it from its settings")
			.containsExactly(MODE)
			;
		// @formatter:on
	}

	@Test
	public void switchWithoutMode() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("ctl"), controlUpdater()),
				List.of(meter("00:00:00", 200)), modeSwitch(" ", ControlPublishMode.Change));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("A switch without a mode is a warning, and the Control Updater logs that it was not handled")
			.containsExactly(SimulationMessage.CODE_INVALID_CONTROL, SimulationMessage.CODE_FILTER_LOG)
			;
		then(step(result.datum().getFirst(), "ctl").instructions())
			.as("Nothing handled the instruction")
			.extracting(InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple(null, null))
			;
		// @formatter:on
	}

	@Test
	public void duplicateControlId() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("ctl"), controlUpdater()),
				List.of(meter("00:00:00", 200)),
				new NodeControlConfig(SWITCH_ID, "Boolean", "false", ControlPublishMode.Never, null),
				modeSwitch(MODE, ControlPublishMode.Change));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("The second control with the ID is left out")
			.containsExactly(SimulationMessage.CODE_DUPLICATE_CONTROL_ID)
			;
		then(step(result.datum().getFirst(), "ctl").instructions())
			.as("The Mock control handled the instruction")
			.extracting(InstructionInfo::handler)
			.containsExactly(new InstructionHandlerInfo(InstructionHandlerInfo.Type.Control, SWITCH_ID))
			;
		then(result.finalState().opModes())
			.as("No mode was enabled")
			.isEmpty()
			;
		// @formatter:on
	}

}
