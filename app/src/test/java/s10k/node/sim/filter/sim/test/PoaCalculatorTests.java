/* ==================================================================
 * PoaCalculatorTests.java - 20 Oct 2026 3:39:11 pm
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
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import s10k.node.sim.filter.domain.CommandInfo;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.FilterStep.Status;
import s10k.node.sim.filter.domain.NodeLocation;
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
 * Test cases for simulating the POA Calculator (pvlib) filter, with stand-in
 * scripts for its command.
 *
 * @author matt
 * @version 1.0
 */
public class PoaCalculatorTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SPEL = SpelExpressionService.class.getName();

	private static final String GHI_DATUM = """
			{"created":"2026-06-01 02:00:00Z","sourceId":"/weather/1","i":{"irradiance":500}}""";

	@TempDir
	private Path tmp;

	private SimulationEngine engine;

	@BeforeEach
	public void setup() {
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	/**
	 * Copy a stand-in command script out of the test resources, so it can run.
	 */
	private String script(String name) throws IOException {
		final Path dest = tmp.resolve(name);
		try (InputStream in = getClass().getResourceAsStream(name)) {
			Files.copy(in, dest);
		}
		dest.toFile().setExecutable(true);
		return dest.toString();
	}

	private static Map<String, String> settings(String command, String... more) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("uid", "POA");
		s.put("command", command);
		s.put("timeZoneId", "UTC");
		for ( int i = 0; i < more.length; i += 2 ) {
			s.put(more[i], more[i + 1]);
		}
		return s;
	}

	private static Map<String, String> located(String command, String... more) {
		Map<String, String> s = settings(command, "lat", "-36.8", "lon", "174.7", "tilt", "30",
				"azimuth", "0");
		for ( int i = 0; i < more.length; i += 2 ) {
			s.put(more[i], more[i + 1]);
		}
		return s;
	}

	private static SimulationRequest request(Map<String, String> settings, SimulationEnvironment env,
			String... allowedCommands) {
		final JsonNode datum = JSON.readTree(GHI_DATUM);
		final List<FilterConfig> filters = List.of(
				new FilterConfig("main", StandardFilterTypes.CHAIN, null, Map.of("uid", "Main"),
						List.of("poa")),
				new FilterConfig("poa", StandardFilterTypes.PVLIB_POA, null, settings, null));
		return new SimulationRequest(new SimConfig(filters, "main"), null, List.of(datum), null, env,
				new SimulationOptions(null, null, null, null, null, null, null,
						List.of(allowedCommands)));
	}

	private static FilterStep poaStep(SimulationResult result) {
		return result.datum().getFirst().steps().get(1);
	}

	@Test
	public void allowedCommand() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa.sh");
		final Map<String, String> settings = located(cmd, "expressionConfigsCount", "1",
				"expressionConfigs[0].name", "direct", "expressionConfigs[0].datumPropertyTypeKey", "i",
				"expressionConfigs[0].expressionServiceId", SPEL, "expressionConfigs[0].expression",
				"poa_direct * 2");

		// WHEN
		SimulationResult result = engine.simulate(request(settings, null, cmd));

		// THEN
		final FilterStep step = poaStep(result);
		// @formatter:off
		then(result.messages())
			.as("No problems")
			.isEmpty()
			;
		then(step)
			.as("The filter changed the datum")
			.returns(Status.Changed, from(FilterStep::status))
			;
		then(step.output().i())
			.as("POA from the command result")
			.containsEntry("irradiance_poa", new BigDecimal("812.5"))
			.as("Expression of the command result")
			.containsEntry("direct", 1200)
			;
		then(step.commands())
			.as("The command ran")
			.singleElement()
			.returns(true, from(CommandInfo::ran))
			.satisfies(c -> {
				then(c.command())
					.as("Command path first")
					.startsWith(cmd)
					.as("Options from the settings and the datum")
					.containsSubsequence("--latitude", "-36.8")
					.containsSubsequence("--array-tilt", "30")
					.containsSubsequence("--irradiance", "500")
					.containsSubsequence("--date", "2026-06-01T02:00")
					.containsSubsequence("--zone", "UTC")
					;
				then(c.result())
					.as("Command result")
					.containsEntry("poa_global", new BigDecimal("812.5"))
					;
			})
			;
		// @formatter:on
	}

	@Test
	public void commandNotAllowed() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa.sh");

		// WHEN
		SimulationResult result = engine.simulate(request(located(cmd), null));

		// THEN
		final FilterStep step = poaStep(result);
		// @formatter:off
		then(result.messages())
			.as("The command is reported when the filter is created, and not again for the chain")
			.singleElement()
			.returns(SimulationMessage.CODE_FILTER_PROBLEM, from(SimulationMessage::code))
			.returns("poa", from(SimulationMessage::filterId))
			.extracting(SimulationMessage::message)
			.asString()
			.contains(cmd, "not in the Allowed Commands list")
			;
		then(step)
			.as("The filter failed")
			.returns(Status.Error, from(FilterStep::status))
			.extracting(FilterStep::error)
			.asString()
			.contains("not in the Allowed Commands list")
			;
		then(step.commands())
			.as("The command did not run")
			.singleElement()
			.returns(false, from(CommandInfo::ran))
			;
		// @formatter:on
	}

	@Test
	public void commandAllowedByServer() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa.sh");
		engine = new SimulationEngine(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService(), List.of(cmd));

		// WHEN
		SimulationResult result = engine.simulate(request(located(cmd), null));

		// THEN
		// @formatter:off
		then(poaStep(result))
			.as("The command the server allows ran")
			.returns(Status.Changed, from(FilterStep::status))
			;
		// @formatter:on
	}

	@Test
	public void nodeLocation() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa.sh");
		final SimulationEnvironment env = new SimulationEnvironment(null, null, null, null, null, null,
				new NodeLocation(new BigDecimal("-41.3"), new BigDecimal("174.8"),
						new BigDecimal("20")));

		// WHEN
		SimulationResult result = engine
				.simulate(request(settings(cmd, "useNodeLocation", "true"), env, cmd));

		// THEN
		// @formatter:off
		then(poaStep(result).commands())
			.as("The command ran")
			.singleElement()
			.extracting(CommandInfo::command)
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
			.as("Location from the Location Service")
			.containsSubsequence("--latitude", "-41.3")
			.containsSubsequence("--longitude", "174.8")
			.containsSubsequence("--altitude", "20")
			;
		// @formatter:on
	}

	@Test
	public void datumMetadata() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa.sh");
		final SimulationEnvironment env = new SimulationEnvironment(null, null, null, null, null,
				Map.of("/weather/1", JSON.readTree("""
						{"pm":{"pv":{"lat":-43.5,"lon":172.6,"pvArrayTilt":15}}}""")), null);

		// WHEN
		SimulationResult result = engine
				.simulate(request(settings(cmd, "metadataPath", "/pm/pv"), env, cmd));

		// THEN
		// @formatter:off
		then(poaStep(result).commands())
			.as("The command ran")
			.singleElement()
			.extracting(CommandInfo::command)
			.asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(String.class))
			.as("Options from the datum metadata")
			.containsSubsequence("--latitude", "-43.5")
			.containsSubsequence("--array-tilt", "15")
			;
		// @formatter:on
	}

	@Test
	public void noLocation() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa.sh");

		// WHEN
		SimulationResult result = engine.simulate(request(settings(cmd), null, cmd));

		// THEN
		// @formatter:off
		then(poaStep(result))
			.as("The datum is unchanged")
			.returns(Status.Unchanged, from(FilterStep::status))
			.as("Without a location, the command does not run")
			.returns(List.of(), from(FilterStep::commands))
			;
		then(result.messages())
			.as("The filter's warning is reported")
			.extracting(SimulationMessage::code, SimulationMessage::filterId)
			.containsExactly(tuple(SimulationMessage.CODE_FILTER_LOG, "poa"))
			;
		then(result.messages().getFirst().message())
			.as("Warning text")
			.contains("No lat/lon available")
			;
		// @formatter:on
	}

	@Test
	public void commandError() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa-error.sh");

		// WHEN
		SimulationResult result = engine.simulate(request(located(cmd), null, cmd));

		// THEN
		final FilterStep step = poaStep(result);
		// @formatter:off
		then(step)
			.as("The filter failed with the command's error output")
			.returns(Status.Error, from(FilterStep::status))
			.extracting(FilterStep::error)
			.asString()
			.contains("not a number")
			;
		then(step.commands())
			.as("The command ran, and failed")
			.singleElement()
			.returns(true, from(CommandInfo::ran))
			.returns(null, from(CommandInfo::result))
			.extracting(CommandInfo::error)
			.asString()
			.contains("not a number")
			;
		// @formatter:on
	}

	@Test
	public void commandTimeout() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa-slow.sh");

		// WHEN
		SimulationResult result = engine
				.simulate(request(located(cmd, "commandTimeoutSeconds", "1"), null, cmd));

		// THEN
		// @formatter:off
		then(poaStep(result))
			.as("The command was stopped")
			.returns(Status.Error, from(FilterStep::status))
			.extracting(FilterStep::error)
			.asString()
			.contains("did not finish within 1s")
			;
		// @formatter:on
	}

	@Test
	public void commandTimeoutLimited() throws IOException {
		// GIVEN
		final String cmd = script("ghi-to-poa.sh");

		// WHEN
		SimulationResult result = engine
				.simulate(request(located(cmd, "commandTimeoutSeconds", "0"), null, cmd));

		// THEN
		// @formatter:off
		then(result.messages())
			.as("Waiting forever is limited")
			.singleElement()
			.returns(SimulationMessage.CODE_FILTER_LIMITATION, from(SimulationMessage::code))
			.extracting(SimulationMessage::message)
			.asString()
			.contains("at most 60 seconds")
			;
		then(poaStep(result))
			.as("The command ran")
			.returns(Status.Changed, from(FilterStep::status))
			;
		// @formatter:on
	}

}
