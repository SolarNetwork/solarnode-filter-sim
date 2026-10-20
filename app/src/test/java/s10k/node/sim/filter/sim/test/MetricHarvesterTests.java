/* ==================================================================
 * MetricHarvesterTests.java - 20 Oct 2026 3:39:11 pm
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.FilterStep.Status;
import s10k.node.sim.filter.domain.MetricInfo;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.domain.SimulationState;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for simulating the Metric Harvester filter.
 *
 * @author matt
 * @version 1.0
 */
public class MetricHarvesterTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private SimulationEngine engine;

	@BeforeEach
	public void setup() {
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	private static String datum(String created, int watts) {
		return """
				{"created":"%s","sourceId":"/meter/1","i":{"watts":%d}}""".formatted(created, watts);
	}

	private static SimulationRequest request(Map<String, String> settings, String... datum) {
		final List<FilterConfig> filters = List.of(
				new FilterConfig("main", StandardFilterTypes.CHAIN, null, Map.of("uid", "Main"),
						List.of("mh")),
				new FilterConfig("mh", StandardFilterTypes.METRIC_HARVESTER, null, settings, null));
		return new SimulationRequest(new SimConfig(filters, "main"), null,
				List.of(datum).stream().map(JSON::readTree).toList(), null, null, null);
	}

	private static Map<String, String> propertySettings() {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Metrics");
		s.put("propertyConfigsCount", "1");
		s.put("propertyConfigs[0].propertyKey", "watts");
		s.put("propertyConfigs[0].propertyTypeKey", "i");
		s.put("propertyConfigs[0].metricName", "power");
		return s;
	}

	private static FilterStep harvesterStep(DatumTrace trace) {
		return trace.steps().get(1);
	}

	@Test
	public void propertyMetrics() {
		// GIVEN
		SimulationRequest req = request(propertySettings(), datum("2026-06-01 00:00:00Z", 100),
				datum("2026-06-01 00:01:00Z", 200));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		final Instant t1 = Instant.parse("2026-06-01T00:00:00Z");
		final Instant t2 = Instant.parse("2026-06-01T00:01:00Z");
		// @formatter:off
		then(result.datum())
			.as("Each datum passed through unchanged")
			.allSatisfy(t -> then(harvesterStep(t))
					.returns(Status.Unchanged, from(FilterStep::status)))
			;
		then(harvesterStep(result.datum().get(0)).metrics())
			.as("First datum metric, with its date")
			.containsExactly(new MetricInfo(t1, "s", "power", 100))
			;
		then(harvesterStep(result.datum().get(1)).metrics())
			.as("Second datum metric")
			.containsExactly(new MetricInfo(t2, "s", "power", 200))
			;
		then(result.finalState())
			.as("Final state has every metric")
			.returns(List.of(new MetricInfo(t1, "s", "power", 100),
					new MetricInfo(t2, "s", "power", 200)), from(SimulationState::metrics))
			;
		// @formatter:on
	}

	@Test
	public void expressionMetrics() {
		// GIVEN
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "Metrics");
		s.put("expressionConfigsCount", "1");
		s.put("expressionConfigs[0].name", "double");
		s.put("expressionConfigs[0].expressionServiceId", SPEL);
		s.put("expressionConfigs[0].expression", "watts * 2");
		SimulationRequest req = request(s, datum("2026-06-01 00:00:00Z", 100));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(harvesterStep(result.datum().getFirst()))
			.as("Expression result saved as a metric")
			.returns(List.of(new MetricInfo(Instant.parse("2026-06-01T00:00:00Z"), "s", "double", 200)),
					from(FilterStep::metrics))
			.as("Expression results do not change the datum")
			.returns(Status.Unchanged, from(FilterStep::status))
			;
		// @formatter:on
	}

	@Test
	public void duplicateMetricIgnored() {
		// GIVEN
		SimulationRequest req = request(propertySettings(), datum("2026-06-01 00:00:00Z", 100),
				datum("2026-06-01 00:00:00Z", 200));

		// WHEN
		SimulationResult result = engine.simulate(req);

		// THEN
		// @formatter:off
		then(harvesterStep(result.datum().get(1)))
			.as("A metric with the same date and name is ignored, like the node's unique key")
			.returns(Status.Unchanged, from(FilterStep::status))
			.returns(List.of(), from(FilterStep::metrics))
			;
		then(result.finalState().metrics())
			.as("Only the first metric kept")
			.containsExactly(new MetricInfo(Instant.parse("2026-06-01T00:00:00Z"), "s", "power", 100))
			;
		// @formatter:on
	}

}
