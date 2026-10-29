/* ==================================================================
 * RunServiceTests.java - 29 Oct 2026 9:50:00 pm
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

package s10k.node.sim.filter.run.test;

import static org.assertj.core.api.BDDAssertions.catchThrowable;
import static org.assertj.core.api.BDDAssertions.then;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.DatumIndexEntry;
import s10k.node.sim.filter.domain.DatumOutcome;
import s10k.node.sim.filter.domain.DatumSourceConfig;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.FilterRunStats;
import s10k.node.sim.filter.domain.RunProgress;
import s10k.node.sim.filter.domain.RunResult;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardDatumSourceTypes;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.run.RunNotFinishedException;
import s10k.node.sim.filter.run.RunNotFoundException;
import s10k.node.sim.filter.run.RunService;
import s10k.node.sim.filter.sim.SimulationEngine;
import s10k.node.sim.filter.sim.SimulationException;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.RawValue;

/**
 * Test cases for simulation runs that run in the background.
 *
 * @author matt
 * @version 1.0
 */
public class RunServiceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String DS = "jobService.datumDataSource.";

	private static final Instant START = Instant.parse("2025-10-01T00:00:00Z");

	private RunService service;

	@BeforeEach
	public void setup() {
		SimulationEngine engine = new SimulationEngine(
				new FilterTypeRegistry(StandardFilterTypes.definitions()), new SpelExpressionService());
		service = new RunService(engine, JSON);
	}

	@AfterEach
	public void teardown() {
		service.close();
	}

	/**
	 * A Main chain with a Throttle that drops all but one datum each 5 minutes.
	 */
	private static List<FilterConfig> filters() {
		Map<String, String> throttle = new LinkedHashMap<>();
		throttle.put("uid", "Throttle");
		throttle.put("frequencySeconds", "300");
		return List.of(
				new FilterConfig("main", StandardFilterTypes.CHAIN, null, Map.of("uid", "Chain - Main"),
						List.of("throttle")),
				new FilterConfig("throttle", StandardFilterTypes.THROTTLE, null, throttle, null));
	}

	/** A Fixed datum source, polled every {@code periodMs}. */
	private static DatumSourceConfig fixed(long periodMs) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("schedule", String.valueOf(periodMs));
		s.put(DS + "uid", "Fixed");
		s.put(DS + "sourceId", "/fixed/1");
		s.put(DS + "propertyConfigsCount", "1");
		s.put(DS + "propertyConfigs[0].propertyKey", "watts");
		s.put(DS + "propertyConfigs[0].propertyTypeKey", "i");
		s.put(DS + "propertyConfigs[0].config", "100");
		return new DatumSourceConfig("fixed", StandardDatumSourceTypes.FIXED, null, s);
	}

	private static SimulationRequest polls(long periodMs, Duration runAfter, int maxDatum) {
		return new SimulationRequest(new SimConfig(filters(), "main"), null, null,
				List.of(fixed(periodMs)), null, null,
				new SimulationOptions(maxDatum, null, null, null, START, null, runAfter, null));
	}

	private static SimulationRequest datum(List<JsonNode> datum) {
		return new SimulationRequest(new SimConfig(filters(), "main"), null, datum, null, null,
				new SimulationOptions(datum.size(), null, null, null, null, null, null, null));
	}

	private RunProgress await(String id) throws InterruptedException {
		final long end = System.currentTimeMillis() + 60_000;
		while ( System.currentTimeMillis() < end ) {
			RunProgress p = service.progress(id);
			if ( p.state() != RunProgress.State.Running ) {
				return p;
			}
			Thread.sleep(20);
		}
		throw new AssertionError("The run did not finish.");
	}

	private List<JsonNode> download(String id, boolean output) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		service.writeDatum(id, output, out);
		List<JsonNode> result = new ArrayList<>();
		JSON.readTree(out.toByteArray()).forEach(result::add);
		return result;
	}

	private static JsonNode json(RawValue value) {
		return JSON.readTree(value.rawValue().toString());
	}

	@Test
	public void dayOfPolls() throws Exception {
		// GIVEN
		SimulationRequest req = polls(60_000, Duration.ofDays(1), 5000);

		// WHEN
		String id = service.start(req).id();
		RunProgress progress = await(id);
		RunResult result = service.result(id);
		List<RawValue> traces = service.traces(id, List.of(1000, 25, 99_999));
		List<JsonNode> inputs = download(id, false);
		List<JsonNode> outputs = download(id, true);

		// THEN
		// @formatter:off
		then(progress)
			.as("Run done")
			.returns(RunProgress.State.Done, RunProgress::state)
			.as("Every poll processed: one at the start, then each minute of the day")
			.returns(1441, RunProgress::processed)
			.as("Expected the polls")
			.returns(1441, RunProgress::expected)
			.as("Date of the last datum")
			.returns(START.plus(Duration.ofDays(1)), RunProgress::date)
			;
		then(result.datum())
			.as("Only the first traces in a long result")
			.hasSize(RunService.FIRST_TRACES)
			;
		then(json(result.datum().getFirst()).get("seq").asInt())
			.as("First trace is the first datum")
			.isEqualTo(1)
			;
		then(result.index())
			.as("Index has every datum")
			.hasSize(1441)
			.as("Index in processing order")
			.extracting(DatumIndexEntry::seq)
			.startsWith(1, 2, 3)
			;
		then(result.index())
			.as("Index has the source IDs")
			.extracting(DatumIndexEntry::sourceId)
			.containsOnly("/fixed/1")
			;
		then(result.index().get(1))
			.as("Index has the date")
			.returns(START.plus(Duration.ofMinutes(1)), DatumIndexEntry::created)
			.as("Throttle dropped the second datum")
			.returns(DatumOutcome.Discarded, DatumIndexEntry::outcome)
			;
		then(result.index().get(5))
			.as("Throttle passed the datum 5 minutes after the first")
			.returns(DatumOutcome.Output, DatumIndexEntry::outcome)
			;
		then(result.filterStats())
			.as("Per-filter counts")
			.containsOnlyKeys("main", "throttle")
			;
		then(result.filterStats().get("throttle"))
			.as("Throttle saw every datum")
			.returns(1441, FilterRunStats::in)
			.as("Throttle passed one datum each 5 minutes")
			.returns(289, FilterRunStats::out)
			.as("Throttle dropped the others")
			.returns(1152, FilterRunStats::dropped)
			;
		then(traces)
			.as("Asked traces loaded, without datum the run did not process")
			.hasSize(2)
			.extracting(t -> json(t).get("seq").asInt())
			.containsExactly(1000, 25)
			;
		then(inputs)
			.as("Input download has every datum")
			.hasSize(1441)
			;
		then(inputs.get(1).toString())
			.as("Input datum in the Datum input format")
			.isEqualTo("""
					{"created":"2025-10-01 00:01:00.000Z","sourceId":"/fixed/1","i":{"watts":100}}""")
			;
		then(outputs)
			.as("Output download has the persisted output datum")
			.hasSize(289)
			;
		// @formatter:on
	}

	@Test
	public void downloadRoundTrip() throws Exception {
		// GIVEN
		String polled = service.start(polls(60_000, Duration.ofHours(2), 1000)).id();
		await(polled);
		List<JsonNode> inputs = download(polled, false);

		// WHEN
		String again = service.start(datum(inputs)).id();
		RunProgress progress = await(again);

		// THEN
		// @formatter:off
		then(progress)
			.as("Run of the downloaded input datum done")
			.returns(RunProgress.State.Done, RunProgress::state)
			.as("Every downloaded datum processed")
			.returns(inputs.size(), RunProgress::processed)
			;
		then(download(again, false))
			.as("Same input datum")
			.isEqualTo(inputs)
			;
		then(download(again, true))
			.as("Same output datum")
			.isEqualTo(download(polled, true))
			;
		// @formatter:on
	}

	@Test
	public void shortRun() throws Exception {
		// GIVEN
		SimulationRequest req = polls(60_000, Duration.ofMinutes(10), 1000);

		// WHEN
		String id = service.start(req).id();
		await(id);
		RunResult result = service.result(id);

		// THEN
		// @formatter:off
		then(result.datum())
			.as("Every trace in a short result")
			.hasSize(11)
			;
		then(result.index())
			.as("No index in a short result")
			.isNull()
			;
		then(result.filterStats())
			.as("No per-filter counts in a short result")
			.isNull()
			;
		// @formatter:on
	}

	@Test
	public void cancel() throws Exception {
		// GIVEN
		SimulationRequest req = polls(1000, Duration.ofDays(2), 100_000);
		String id = service.start(req).id();
		RunProgress running = service.progress(id);
		final long end = System.currentTimeMillis() + 30_000;
		while ( running.processed() < 10 && System.currentTimeMillis() < end ) {
			Thread.sleep(5);
			running = service.progress(id);
		}
		Throwable notFinished = catchThrowable(() -> service.result(id));

		// WHEN
		boolean cancelled = service.cancel(id);
		RunProgress progress = await(id);
		RunResult result = service.result(id);

		// THEN
		// @formatter:off
		then(running)
			.as("Running before the cancel")
			.returns(RunProgress.State.Running, RunProgress::state)
			.as("Expected the polls, up to Maximum Datum")
			.returns(100_000, RunProgress::expected)
			;
		then(running.processed())
			.as("Progress shows datum processed")
			.isGreaterThanOrEqualTo(10)
			;
		then(notFinished)
			.as("No result while running")
			.isInstanceOf(RunNotFinishedException.class)
			;
		then(cancelled)
			.as("Run was running")
			.isTrue()
			;
		then(progress)
			.as("Cancelled run is done")
			.returns(RunProgress.State.Done, RunProgress::state)
			;
		then(progress.processed())
			.as("Stopped before the end")
			.isLessThan(100_000)
			;
		then(result.messages())
			.as("Cancelled message")
			.extracting(SimulationMessage::code)
			.contains(SimulationMessage.CODE_CANCELLED)
			;
		then(result.stats().processed())
			.as("Result keeps what was processed")
			.isEqualTo(progress.processed())
			;
		then(service.cancel(id))
			.as("A finished run cannot be cancelled")
			.isFalse()
			;
		// @formatter:on
	}

	@Test
	public void dropsOldRuns() throws Exception {
		// GIVEN
		List<String> ids = new ArrayList<>();
		for ( int i = 0; i < RunService.KEEP_RUNS + 1; i++ ) {
			String id = service.start(polls(60_000, Duration.ofMinutes(i), 1000)).id();
			await(id);
			ids.add(id);
		}

		// WHEN
		Throwable oldest = catchThrowable(() -> service.result(ids.getFirst()));
		List<Integer> kept = ids.subList(1, ids.size()).stream()
				.map(id -> service.result(id).stats().processed()).toList();

		// THEN
		// @formatter:off
		then(oldest)
			.as("Oldest run dropped")
			.isInstanceOf(RunNotFoundException.class)
			;
		then(kept)
			.as("Newer runs kept")
			.containsExactly(2, 3, 4)
			;
		// @formatter:on
	}

	@Test
	public void invalidRequest() {
		// GIVEN
		SimulationRequest req = datum(List.of(JSON.readTree("""
				{"i":{"watts":1}}""")));

		// WHEN
		Throwable t = catchThrowable(() -> service.start(req));

		// THEN
		// @formatter:off
		then(t)
			.as("Invalid request fails at once")
			.isInstanceOf(SimulationException.class)
			;
		// @formatter:on
	}

	@Test
	public void unknownRun() {
		// WHEN
		Throwable t = catchThrowable(() -> service.progress("nope"));

		// THEN
		// @formatter:off
		then(t)
			.as("Unknown run not found")
			.isInstanceOf(RunNotFoundException.class)
			;
		// @formatter:on
	}

}
