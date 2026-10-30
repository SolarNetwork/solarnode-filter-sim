/* ==================================================================
 * SimulationLimitsTests.java - 29 Oct 2026 9:45:00 pm
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

import static org.assertj.core.api.BDDAssertions.catchThrowable;
import static org.assertj.core.api.BDDAssertions.then;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import s10k.node.sim.filter.sim.SimulationException;
import s10k.node.sim.filter.sim.SimulationLimits;
import s10k.node.sim.filter.sim.SimulationLimits.RunLimits;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for the server's limits on simulations, and how each run's limits
 * scale with its Maximum Datum.
 *
 * @author matt
 * @version 1.0
 */
public class SimulationLimitsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static SimulationOptions options(Integer maxDatum, Long timeoutMs) {
		return new SimulationOptions(maxDatum, null, timeoutMs, null, null, null, null, null);
	}

	@Test
	public void defaults() {
		// WHEN
		RunLimits limits = SimulationLimits.DEFAULTS.runLimits(options(null, null));

		// THEN
		// @formatter:off
		then(limits)
			.as("Maximum Datum defaults to 1,000")
			.returns(SimulationOptions.DEFAULT_MAX_DATUM, RunLimits::maxDatum)
			.as("Timeout is 10 s for 1,000 datum")
			.returns(10_000L, RunLimits::timeoutMs)
			.as("Task runs are at least the minimum")
			.returns(SimulationLimits.MIN_TASK_RUNS, RunLimits::maxTaskRuns)
			.as("Not limited by the server")
			.returns(false, RunLimits::datumLimited)
			;
		// @formatter:on
	}

	@Test
	public void scaled() {
		// WHEN
		RunLimits limits = SimulationLimits.DEFAULTS.runLimits(options(5001, null));

		// THEN
		// @formatter:off
		then(limits)
			.as("Maximum Datum as requested")
			.returns(5001, RunLimits::maxDatum)
			.as("Timeout is 10 s per 1,000 datum, rounded up")
			.returns(60_000L, RunLimits::timeoutMs)
			.as("Task runs are 10 per datum")
			.returns(50_010, RunLimits::maxTaskRuns)
			;
		// @formatter:on
	}

	@Test
	public void small() {
		// WHEN
		RunLimits limits = SimulationLimits.DEFAULTS.runLimits(options(5, null));

		// THEN
		// @formatter:off
		then(limits)
			.as("Timeout is at least 10 s")
			.returns(10_000L, RunLimits::timeoutMs)
			.as("Task runs are at least the minimum")
			.returns(SimulationLimits.MIN_TASK_RUNS, RunLimits::maxTaskRuns)
			;
		// @formatter:on
	}

	@Test
	public void serverLimits() {
		// GIVEN
		SimulationLimits server = new SimulationLimits(20_000, Duration.ofMinutes(1));

		// WHEN
		RunLimits limits = server.runLimits(options(50_000, null));
		RunLimits explicit = server.runLimits(options(10, 120_000L));

		// THEN
		// @formatter:off
		then(limits)
			.as("Maximum Datum limited by the server")
			.returns(20_000, RunLimits::maxDatum)
			.as("Limited by the server")
			.returns(true, RunLimits::datumLimited)
			.as("Timeout limited by the server's run time")
			.returns(60_000L, RunLimits::timeoutMs)
			;
		then(explicit)
			.as("Requested timeout limited by the server's run time")
			.returns(60_000L, RunLimits::timeoutMs)
			;
		// @formatter:on
	}

	@Test
	public void invalidServerLimits() {
		// WHEN
		SimulationLimits limits = new SimulationLimits(0, Duration.ZERO);

		// THEN
		// @formatter:off
		then(limits)
			.as("Defaults used for invalid values")
			.isEqualTo(SimulationLimits.DEFAULTS)
			;
		// @formatter:on
	}

	@Test
	public void datumLimitedMessage() {
		// GIVEN
		SimulationEngine engine = new SimulationEngine(
				new FilterTypeRegistry(StandardFilterTypes.definitions()),
				DatumSourceTypeRegistry.standard(), new SpelExpressionService(), List.of(),
				new SimulationLimits(2, Duration.ofMinutes(1)));
		JsonNode datum = JSON.readTree("""
				[{"sourceId":"a","i":{"x":1}},{"sourceId":"a","i":{"x":2}},{"sourceId":"a","i":{"x":3}}]
				""");
		SimConfig config = new SimConfig(List.of(new FilterConfig("main", StandardFilterTypes.CHAIN,
				null, Map.of("uid", "Chain - Main"), List.of())), "main");
		SimulationRequest req = new SimulationRequest(config, null, List.of(datum.get(0), datum.get(1)),
				null, null, options(5, null));
		SimulationRequest tooMany = new SimulationRequest(config, null,
				List.of(datum.get(0), datum.get(1), datum.get(2)), null, null, options(5, null));

		// WHEN
		SimulationResult result = engine.simulate(req);
		Throwable tooManyError = catchThrowable(() -> engine.simulate(tooMany));

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Datum limited message added")
			.extracting(SimulationMessage::code)
			.contains(SimulationMessage.CODE_DATUM_LIMIT)
			;
		then(result.datum())
			.as("Datum processed")
			.hasSize(2)
			;
		then(tooManyError)
			.as("More input datum than the server allows is an error")
			.isInstanceOf(SimulationException.class)
			;
		// @formatter:on
	}

}
