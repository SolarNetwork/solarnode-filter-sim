/* ==================================================================
 * DatumSourceTests.java - 29 Oct 2026 8:30:00 pm
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
import java.util.ArrayList;
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
import s10k.node.sim.filter.domain.DatumSourceConfig;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.InstructionHandlerInfo;
import s10k.node.sim.filter.domain.InstructionInfo;
import s10k.node.sim.filter.domain.NodeControlConfig;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
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
 * Test cases for datum sources, polled on their schedules.
 *
 * @author matt
 * @version 1.0
 */
public class DatumSourceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private static final String DS = "jobService.datumDataSource.";

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

	private static Map<String, String> settings(String schedule, String... keyValues) {
		Map<String, String> s = new LinkedHashMap<>();
		if ( schedule != null ) {
			s.put("schedule", schedule);
		}
		for ( int i = 0; i + 1 < keyValues.length; i += 2 ) {
			s.put(keyValues[i].startsWith("schedule") || keyValues[i].startsWith("job") ? keyValues[i]
					: DS + keyValues[i], keyValues[i + 1]);
		}
		return s;
	}

	private static DatumSourceConfig fixed(String id, String schedule, String sourceId) {
		return new DatumSourceConfig(id, StandardDatumSourceTypes.FIXED, null,
				settings(schedule, "uid", "Fixed", "sourceId", sourceId, "propertyConfigsCount", "1",
						"propertyConfigs[0].propertyKey", "watts", "propertyConfigs[0].propertyTypeKey",
						"i", "propertyConfigs[0].config", "100"));
	}

	private static DatumSourceConfig battery(String schedule, ControlPublishMode publish) {
		return new DatumSourceConfig("battery", StandardDatumSourceTypes.MOCK_BATTERY, null,
				settings(schedule, "uid", "Battery", "sourceId", "/bat/1", "targetPowerRateControlId",
						"/bat/rate", "socControlId", "/bat/soc", "energyCapacity", "10000", "initialSoc",
						"50"),
				publish, null);
	}

	private static DatumSourceConfig meterSource(String id, String schedule, boolean random) {
		return new DatumSourceConfig(id, StandardDatumSourceTypes.MOCK_ENERGY_METER, null,
				settings(schedule, "uid", "Meter", "sourceId", "/meter/mock", "randomness",
						String.valueOf(random), "voltdev", "10", "currentDeviation", "2"));
	}

	/**
	 * A Control Updater that sets a control from an expression on
	 * {@code /meter/1}.
	 */
	private static FilterConfig controlUpdater(String controlId, String expression) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Control Updater");
		s.put("sourceId", "^/meter/1$");
		s.put("controlConfigsCount", "1");
		s.put("controlConfigs[0].controlId", controlId);
		s.put("controlConfigs[0].expressionServiceId", SPEL);
		s.put("controlConfigs[0].expression", expression);
		return new FilterConfig("ctl", StandardFilterTypes.CONTROL_UPDATER, null, s, null);
	}

	/** An Expression filter that adds a property to every datum. */
	private static FilterConfig expression(String name, String expression) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Expression");
		s.put("expressionConfigsCount", "1");
		s.put("expressionConfigs[0].name", name);
		s.put("expressionConfigs[0].datumPropertyTypeKey", "s");
		s.put("expressionConfigs[0].expressionServiceId", SPEL);
		s.put("expressionConfigs[0].expression", expression);
		return new FilterConfig("exp", StandardFilterTypes.EXPRESSION, null, s, null);
	}

	private static SimulationOptions options(Instant startDate, Duration runAfter) {
		return new SimulationOptions(null, null, null, null, startDate, null, runAfter, null);
	}

	private static SimulationRequest request(List<FilterConfig> filters, List<JsonNode> datum,
			List<DatumSourceConfig> sources, SimulationEnvironment env, SimulationOptions options) {
		return new SimulationRequest(new SimConfig(filters, "main"), null, datum, sources, null, env,
				options);
	}

	private static List<String> codes(SimulationResult result) {
		return result.messages().stream().map(SimulationMessage::code).toList();
	}

	private static FilterStep step(DatumTrace trace, String filterId) {
		return trace.steps().stream().filter(s -> s.filterId().equals(filterId)).findFirst()
				.orElseThrow();
	}

	private static List<DatumTrace> polled(SimulationResult result, String sourceId) {
		return result.datum().stream().filter(t -> sourceId.equals(t.input().sourceId())).toList();
	}

	private static Number number(Object o) {
		return (o instanceof Number n ? n : new java.math.BigDecimal(o.toString()));
	}

	@Test
	public void periodic_betweenStaticDatum() {
		// GIVEN
		SimulationRequest req = request(List.of(chain()),
				List.of(meter("00:00:00", 1), meter("00:01:00", 2)),
				List.of(fixed("fixed", "30000", "/fixed/1")), null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum())
			.as("A periodic schedule polls first at the start, after a static datum of the same time, and then before later datum")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp(), t -> t.origin().type())
			.containsExactly(
					tuple("/meter/1", at("00:00:00"), DatumOrigin.Type.Input),
					tuple("/fixed/1", at("00:00:00"), DatumOrigin.Type.Polled),
					tuple("/fixed/1", at("00:00:30"), DatumOrigin.Type.Polled),
					tuple("/meter/1", at("00:01:00"), DatumOrigin.Type.Input))
			;
		then(result.datum().get(1).origin())
			.as("Polled origin names the datum source")
			.returns("fixed", from(DatumOrigin::datumSourceId))
			;
		then(result.datum().get(1).input().samples().i())
			.as("Fixed property polled")
			.hasEntrySatisfying("watts", v -> then(number(v).intValue()).isEqualTo(100))
			;
		then(result.stats())
			.as("Polled datum counted")
			.returns(2, from(s -> s.polled()))
			.returns(0, from(s -> s.generated()))
			;
		// @formatter:on
	}

	@Test
	public void cron_onlyDatumSources_keepRunning() {
		// GIVEN
		SimulationRequest req = request(List.of(chain()), List.of(),
				List.of(fixed("fixed", "0 * * * * ?", "/fixed/1")), null,
				options(at("00:00:30"), Duration.ofMinutes(3)));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum())
			.as("A cron schedule polls at its times after the start, up to the end of Keep Running")
			.extracting(t -> t.input().timestamp())
			.containsExactly(at("00:01:00"), at("00:02:00"), at("00:03:00"))
			;
		// @formatter:on
	}

	@Test
	public void cron_notPolled_info() {
		// GIVEN
		SimulationRequest req = request(List.of(chain()),
				List.of(meter("00:00:10", 1), meter("00:00:20", 2)),
				List.of(fixed("fixed", null, "/fixed/1")), null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Datum source not polled in the window: the default schedule is each minute")
			.extracting(SimulationMessage::code, SimulationMessage::datumSourceId)
			.containsExactly(tuple(SimulationMessage.CODE_DATUM_SOURCE_POLLS, "fixed"))
			;
		then(result.messages().getFirst().message())
			.as("First poll time given")
			.contains("2025-10-01T00:01:00Z")
			;
		// @formatter:on
	}

	@Test
	public void invalidConfigurations() {
		// GIVEN
		List<DatumSourceConfig> sources = List.of(fixed("bad", "every minute", "/fixed/1"),
				fixed("zero", "0", "/fixed/2"), fixed("negative", "-1", "/fixed/3"),
				fixed("blank", "1000", ""),
				new DatumSourceConfig("modbus", "net.solarnetwork.node.datum.modbus", null, Map.of()));
		SimulationRequest req = request(List.of(chain()), List.of(meter("00:00:00", 1)), sources, null,
				options(at("00:00:00"), Duration.ofSeconds(2)));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Each problem is a warning about its datum source, and a blank Source ID polls no datum")
			.extracting(SimulationMessage::code, SimulationMessage::datumSourceId)
			.containsExactly(
					tuple(SimulationMessage.CODE_INVALID_DATUM_SOURCE, "modbus"),
					tuple(SimulationMessage.CODE_INVALID_DATUM_SOURCE, "bad"),
					tuple(SimulationMessage.CODE_INVALID_DATUM_SOURCE, "zero"),
					tuple(SimulationMessage.CODE_INVALID_DATUM_SOURCE, "negative"),
					tuple(SimulationMessage.CODE_INVALID_DATUM_SOURCE, "blank"),
					tuple(SimulationMessage.CODE_DATUM_SOURCE_POLLS, "blank"))
			;
		then(result.datum())
			.as("Only the static datum")
			.hasSize(1)
			;
		// @formatter:on
	}

	@Test
	public void legacyKeys() {
		// GIVEN
		Map<String, String> s = new LinkedHashMap<>();
		s.put("triggerCronExpression", "30000");
		String prefix = "jobDetail.jobDataMap['datumDataSource'].";
		s.put(prefix + "sourceId", "/fixed/1");
		s.put(prefix + "propertyConfigsCount", "1");
		s.put(prefix + "propertyConfigs[0].propertyKey", "watts");
		s.put(prefix + "propertyConfigs[0].propertyTypeKey", "i");
		s.put(prefix + "propertyConfigs[0].config", "100");
		SimulationRequest req = request(List.of(chain()), List.of(meter("00:00:00", 1)),
				List.of(new DatumSourceConfig("fixed", StandardDatumSourceTypes.FIXED, null, s)), null,
				options(at("00:00:00"), Duration.ofSeconds(30)));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(polled(result, "/fixed/1"))
			.as("SolarNode 1.x keys work")
			.extracting(t -> t.input().timestamp())
			.containsExactly(at("00:00:00"), at("00:00:30"))
			;
		// @formatter:on
	}

	@Test
	public void battery_controlUpdaterSetsRate() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("ctl"), controlUpdater("/bat/rate", "watts")),
				List.of(meter("00:00:00", 0), meter("00:00:30", 1500)),
				List.of(battery("60000", ControlPublishMode.Change)), null,
				options(null, Duration.ofMinutes(2)));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.datum())
			.as("Polls, the static datum, and the rate control's datum when it changes")
			.extracting(t -> t.input().sourceId(), t -> t.input().timestamp(), t -> t.origin().type())
			.containsExactly(
					tuple("/meter/1", at("00:00:00"), DatumOrigin.Type.Input),
					tuple("/bat/1", at("00:00:00"), DatumOrigin.Type.Polled),
					tuple("/meter/1", at("00:00:30"), DatumOrigin.Type.Input),
					tuple("/bat/rate", at("00:00:30"), DatumOrigin.Type.Generated),
					tuple("/bat/1", at("00:01:00"), DatumOrigin.Type.Polled),
					tuple("/bat/1", at("00:02:00"), DatumOrigin.Type.Polled))
			;
		FilterStep ctl = step(result.datum().get(2), "ctl");
		then(ctl.instructions())
			.as("The battery completed the instruction")
			.extracting(InstructionInfo::state, InstructionInfo::handler)
			.containsExactly(tuple("Completed",
					new InstructionHandlerInfo(InstructionHandlerInfo.Type.DatumSource, "battery")))
			;
		then(ctl.controls())
			.as("The rate set, limited to the maximum charge rate")
			.extracting(ControlUpdate::controlId, ControlUpdate::previousValue, ControlUpdate::value,
					ControlUpdate::requested, ControlUpdate::component, ControlUpdate::published)
			.containsExactly(tuple("/bat/rate", "0", "1000", "1500", "battery", true))
			;
		List<Number> energy = polled(result, "/bat/1").stream()
				.map(t -> number(t.input().samples().i().get("availWattHours"))).toList();
		then(energy)
			.as("The rate charges the battery from when it was set")
			.extracting(Number::intValue)
			.containsExactly(5000, 5008, 5025)
			;
		then(result.finalState().controls())
			.as("The battery's controls, with the rate read after the run")
			.extracting(ControlInfo::controlId, ControlInfo::value, ControlInfo::initialValue,
					ControlInfo::updates, ControlInfo::component)
			.containsExactly(
					tuple("/bat/rate", "1000", "0", 2, "battery"),
					tuple("/bat/soc", "50.25", "50.0", 0, "battery"))
			;
		// @formatter:on
	}

	@Test
	public void battery_socSet_sameRate_never() {
		// GIVEN
		SimulationRequest req = request(
				List.of(chain("ctl"), controlUpdater("/bat/soc", "watts == 1 ? 80 : 'x'")),
				List.of(meter("00:00:30", 1), meter("00:00:40", 2)),
				List.of(battery("60000", ControlPublishMode.Never)), null, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Never publishes no control datum")
			.extracting(t -> t.input().sourceId())
			.containsExactly("/meter/1", "/bat/1", "/meter/1")
			;
		then(step(result.datum().get(0), "ctl").controls())
			.as("The SOC set, without the value before")
			.extracting(ControlUpdate::controlId, ControlUpdate::previousValue, ControlUpdate::value,
					ControlUpdate::requested, ControlUpdate::published)
			.containsExactly(tuple("/bat/soc", null, "80.0", null, false))
			;
		then(step(result.datum().get(2), "ctl").instructions())
			.as("A value that is not a number is declined")
			.extracting(InstructionInfo::state)
			.containsExactly("Declined")
			;
		// @formatter:on
	}

	@Test
	public void battery_controlIdUsedByControl() {
		// GIVEN
		SimulationEnvironment env = new SimulationEnvironment(null, null, null, null, null, null, null,
				List.of(new NodeControlConfig("/bat/rate", "Float", "0", null, null)), null);
		SimulationRequest req = request(List.of(chain("ctl"), controlUpdater("/bat/rate", "watts")),
				List.of(meter("00:00:30", 500)), List.of(battery("60000", ControlPublishMode.Change)),
				env, null);

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("The shared control ID is a warning")
			.containsExactly(SimulationMessage.CODE_INVALID_DATUM_SOURCE,
					SimulationMessage.CODE_DATUM_SOURCE_POLLS)
			;
		then(step(result.datum().getFirst(), "ctl").instructions())
			.as("The simulated control handles the instruction")
			.extracting(InstructionInfo::handler)
			.containsExactly(new InstructionHandlerInfo(InstructionHandlerInfo.Type.Control, "/bat/rate"))
			;
		// @formatter:on
	}

	@Test
	public void energyMeter_deviceInfo_twoRuns() {
		// GIVEN
		SimulationRequest req = request(List.of(chain("exp"), expression("maker",
				"sourceId == '/meter/mock' ? meta(sourceId)?.metadataAtPath('/pm/deviceInfo/manufacturer') : null")),
				List.of(), List.of(meterSource("meter", "60000", false)), null,
				options(at("00:00:00"), Duration.ofMinutes(1)));

		// WHEN
		List<SimulationResult> results = List.of(engine.simulate(req), engine.simulate(req));

		// THEN
		for ( SimulationResult result : results ) {
			// @formatter:off
			then(result.datum())
				.as("Device info published by every run's first poll, before its datum is processed")
				.extracting(t -> t.output().samples().s() != null
						? t.output().samples().s().get("maker") : null)
				.containsExactly("SolarNetwork", "SolarNetwork")
				;
			// @formatter:on
		}
	}

	@Test
	public void energyMeter_random_repeats() {
		// GIVEN
		SimulationRequest req = request(List.of(chain()), List.of(),
				List.of(meterSource("meter", "1000", true)), null,
				options(at("00:00:00"), Duration.ofSeconds(5)));

		// WHEN
		List<List<Object>> runs = new ArrayList<>();
		for ( int i = 0; i < 2; i++ ) {
			runs.add(engine.simulate(req).datum().stream().map(t -> (Object) t.input().samples().i())
					.toList());
		}

		// THEN
		// @formatter:off
		then(runs.get(0))
			.as("Six polls")
			.hasSize(6)
			;
		then(runs.get(1))
			.as("Random deviations repeat")
			.isEqualTo(runs.get(0))
			;
		// @formatter:on
	}

	@Test
	public void tou_tariffScheduleExpression() {
		// GIVEN
		JsonNode nodeMeta = JSON.readTree("""
				{"pm":{"tariffs":{"schedule":[["Month","Day","Weekday","Time","E"],
				["","","","","0.25"]]}}}""");
		SimulationEnvironment env = new SimulationEnvironment(null, null, nodeMeta, null, null, null,
				null, null, null);
		DatumSourceConfig tou = new DatumSourceConfig("tou", StandardDatumSourceTypes.TOU, null,
				settings("60000", "uid", "TOU", "sourceId", "/tou/1", "metadataPath",
						"/pm/tariffs/schedule", "propertyConfigsCount", "1",
						"propertyConfigs[0].rateName", "E", "propertyConfigs[0].propertyKey", "price",
						"propertyConfigs[0].propertyTypeKey", "i"));
		SimulationRequest req = request(
				List.of(chain("exp"),
						expression("rate",
								"tariffSchedule('TOU').resolveTariff(now(), null).rates['E'].amount")),
				List.of(meter("00:00:00", 1)), List.of(tou), env, options(null, Duration.ofSeconds(1)));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(codes(result))
			.as("Only the time zone limitation")
			.containsExactly(SimulationMessage.CODE_FILTER_LIMITATION)
			;
		then(result.datum())
			.as("The TOU source's schedule is available to expressions, and its datum has the rate")
			.extracting(t -> t.input().sourceId(), t -> String.valueOf(t.output().samples().s().get("rate")),
					t -> t.input().samples().i().get("price"))
			.containsExactly(
					tuple("/meter/1", "0.25", null),
					tuple("/tou/1", "0.25", new java.math.BigDecimal("0.25")))
			;
		// @formatter:on
	}

	@Test
	public void battery_summaryAndDebug_same() {
		// GIVEN
		List<FilterConfig> filters = List.of(chain("ctl"), controlUpdater("/bat/rate", "watts"));
		List<JsonNode> datum = List.of(meter("00:00:00", 400), meter("00:00:30", -300));
		List<DatumSourceConfig> sources = List.of(battery("20000", ControlPublishMode.Change));
		SimulationRequest summary = request(filters, datum, sources, null,
				options(null, Duration.ofMinutes(1)));
		SimulationRequest debug = request(filters, datum, sources, null, new SimulationOptions(null,
				null, null, SimulationOptions.Detail.Debug, null, null, Duration.ofMinutes(1), null));

		// WHEN
		List<Object> a = engine.simulate(summary).datum().stream().map(t -> (Object) t.input()).toList();
		List<Object> b = engine.simulate(debug).datum().stream().map(t -> (Object) t.input()).toList();

		// THEN
		// @formatter:off
		then(b)
			.as("Debug snapshots do not change the battery")
			.isEqualTo(a)
			;
		// @formatter:on
	}

	@Test
	public void battery_conductorTaskSetsRate() {
		// GIVEN
		Map<String, String> instr = new LinkedHashMap<>();
		instr.put("uid", "Instructor");
		instr.put("sourceId", "^/meter/1$");
		instr.put("instructorConfigsCount", "1");
		instr.put("instructorConfigs[0].predicate.expressionServiceId", SPEL);
		instr.put("instructorConfigs[0].predicate.expression", "true");
		instr.put("instructorConfigs[0].instructionsCount", "1");
		instr.put("instructorConfigs[0].instructions[0].topic", "OrchestrateControls");
		instr.put("instructorConfigs[0].instructions[0].parametersCount", "1");
		instr.put("instructorConfigs[0].instructions[0].parameters[0].name", "service");
		instr.put("instructorConfigs[0].instructions[0].parameters[0].expression", "Charge");
		Map<String, String> cc = new LinkedHashMap<>();
		cc.put("uid", "Charge");
		cc.put("taskConfigsCount", "1");
		cc.put("taskConfigs[0].controlId", "/bat/rate");
		cc.put("taskConfigs[0].executionOffset", "PT10S");
		cc.put("taskConfigs[0].value", "800");
		SimulationEnvironment env = new SimulationEnvironment(null, null, null, null, null, null, null,
				null, List.of(new ControlConductorConfig(cc)));
		SimulationRequest req = request(
				List.of(chain("instr"),
						new FilterConfig("instr", StandardFilterTypes.INSTRUCTOR, null, instr, null)),
				List.of(meter("00:00:00", 1)), List.of(battery("60000", ControlPublishMode.Never)), env,
				options(null, Duration.ofMinutes(1)));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(result.messages())
			.as("No messages")
			.isEmpty()
			;
		then(result.instructionRuns())
			.as("The conductor's task, run by the job, set the battery's rate")
			.extracting(r -> r.date(), r -> r.instruction().handler(), r -> r.controls())
			.containsExactly(tuple(at("00:00:10"),
					new InstructionHandlerInfo(InstructionHandlerInfo.Type.Conductor, "Charge"),
					List.of(new ControlUpdate("/bat/rate", "Float", "0", "800", false, null, "battery",
							null))))
			;
		then(polled(result, "/bat/1"))
			.as("The next poll has the new rate")
			.extracting(t -> number(t.input().samples().i().get("powerRate")).intValue())
			.containsExactly(0, 800)
			;
		// @formatter:on
	}

}
