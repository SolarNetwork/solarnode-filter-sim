/* ==================================================================
 * RunControllerTests.java - 29 Oct 2026 10:05:00 pm
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

package s10k.node.sim.filter.web.test;

import static org.assertj.core.api.BDDAssertions.then;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import s10k.node.sim.filter.domain.DatumSourceConfig;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.RunProgress;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardDatumSourceTypes;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.run.RunService;
import s10k.node.sim.filter.sim.SimulationEngine;
import s10k.node.sim.filter.web.ApiExceptionHandler;
import s10k.node.sim.filter.web.RunController;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Test cases for the run web API.
 *
 * @author matt
 * @version 1.0
 */
public class RunControllerTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String DS = "jobService.datumDataSource.";

	private RunService service;
	private MockMvc mvc;

	@BeforeEach
	public void setup() {
		SimulationEngine engine = new SimulationEngine(
				new FilterTypeRegistry(StandardFilterTypes.definitions()), new SpelExpressionService());
		service = new RunService(engine, JSON);
		mvc = MockMvcBuilders.standaloneSetup(new RunController(service))
				.setControllerAdvice(new ApiExceptionHandler()).build();
	}

	@AfterEach
	public void teardown() {
		service.close();
	}

	private static String request(long periodMs, Duration runAfter, int maxDatum) {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("schedule", String.valueOf(periodMs));
		s.put(DS + "uid", "Fixed");
		s.put(DS + "sourceId", "/fixed/1");
		s.put(DS + "propertyConfigsCount", "1");
		s.put(DS + "propertyConfigs[0].propertyKey", "watts");
		s.put(DS + "propertyConfigs[0].propertyTypeKey", "i");
		s.put(DS + "propertyConfigs[0].config", "100");
		SimulationRequest req = new SimulationRequest(
				new SimConfig(List.of(new FilterConfig("main", StandardFilterTypes.CHAIN, null,
						Map.of("uid", "Chain - Main"), List.of())), "main"),
				null, null,
				List.of(new DatumSourceConfig("fixed", StandardDatumSourceTypes.FIXED, null, s)), null,
				null, new SimulationOptions(maxDatum, null, null, null,
						Instant.parse("2025-10-01T00:00:00Z"), null, runAfter, null));
		return JSON.writeValueAsString(req);
	}

	private String start(String body) throws Exception {
		MvcResult r = mvc
				.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content(body))
				.andReturn();
		then(r.getResponse().getStatus()).as("Run started").isEqualTo(200);
		return JSON.readTree(r.getResponse().getContentAsString()).get("id").asString();
	}

	/** The events of an event stream, as name and data pairs. */
	private static List<Map.Entry<String, JsonNode>> events(String stream) {
		List<Map.Entry<String, JsonNode>> result = new ArrayList<>();
		String name = null;
		for ( String line : stream.split("\n") ) {
			if ( line.startsWith("event:") ) {
				name = line.substring(6);
			} else if ( line.startsWith("data:") && name != null ) {
				result.add(Map.entry(name, JSON.readTree(line.substring(5))));
				name = null;
			}
		}
		return result;
	}

	@Test
	public void progressEvents() throws Exception {
		// GIVEN
		String id = start(request(1000, Duration.ofDays(1), 30_000));

		// WHEN
		MvcResult async = mvc.perform(get("/api/runs/{id}/events", id)).andReturn();
		async.getAsyncResult(60_000);
		List<Map.Entry<String, JsonNode>> events = events(async.getResponse().getContentAsString());

		// THEN
		// @formatter:off
		then(events)
			.as("Progress events, then done")
			.extracting(Map.Entry::getKey)
			.startsWith("progress")
			.endsWith("done")
			.containsOnly("progress", "done")
			;
		then(events.getFirst().getValue().get("expected").asInt())
			.as("Progress expects the polls")
			.isEqualTo(30_000)
			;
		then(events.getLast().getValue().get("state").asString())
			.as("Done event has the final state")
			.isEqualTo(RunProgress.State.Done.name())
			;
		then(events.getLast().getValue().get("processed").asInt())
			.as("Done event has the datum processed")
			.isEqualTo(30_000)
			;
		// @formatter:on
	}

	@Test
	public void eventsAfterDone() throws Exception {
		// GIVEN
		String id = start(request(60_000, Duration.ofMinutes(5), 1000));
		while ( !RunProgress.State.Done.equals(service.progress(id).state()) ) {
			Thread.sleep(10);
		}

		// WHEN
		MvcResult async = mvc.perform(get("/api/runs/{id}/events", id)).andReturn();
		async.getAsyncResult(10_000);
		List<Map.Entry<String, JsonNode>> events = events(async.getResponse().getContentAsString());

		// THEN
		// @formatter:off
		then(events)
			.as("Only a done event for a run that has ended")
			.extracting(Map.Entry::getKey)
			.containsExactly("done")
			;
		// @formatter:on
	}

	@Test
	public void resultsAndDownloads() throws Exception {
		// GIVEN
		String id = start(request(60_000, Duration.ofDays(1), 5000));
		while ( !RunProgress.State.Done.equals(service.progress(id).state()) ) {
			Thread.sleep(10);
		}

		// WHEN
		JsonNode result = JSON.readTree(
				mvc.perform(get("/api/runs/{id}", id)).andReturn().getResponse().getContentAsString());
		JsonNode traces = JSON
				.readTree(mvc.perform(get("/api/runs/{id}/datum", id).param("seq", "30,500")).andReturn()
						.getResponse().getContentAsString());
		MvcResult download = mvc.perform(get("/api/runs/{id}/datum.json", id).param("kind", "input"))
				.andReturn();
		mvc.perform(asyncDispatch(download));

		// THEN
		// @formatter:off
		then(result.get("datum").size())
			.as("First traces")
			.isEqualTo(RunService.FIRST_TRACES)
			;
		then(result.get("index").size())
			.as("Index of every datum")
			.isEqualTo(1441)
			;
		then(result.get("filterStats").get("main").get("in").asInt())
			.as("Per-filter counts")
			.isEqualTo(1441)
			;
		then(traces.size())
			.as("Traces loaded")
			.isEqualTo(2)
			;
		then(traces.get(1).get("seq").asInt())
			.as("Traces in the order asked")
			.isEqualTo(500)
			;
		then(download.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
			.as("Download file name has the kind and start date")
			.isEqualTo("attachment; filename=\"solarnode-filter-sim-input-20251001T0000Z.json\"")
			;
		then(JSON.readTree(download.getResponse().getContentAsString()).size())
			.as("Download has every input datum")
			.isEqualTo(1441)
			;
		// @formatter:on
	}

	@Test
	public void notFoundAndNotFinished() throws Exception {
		// GIVEN
		String id = start(request(1000, Duration.ofDays(2), 100_000));

		// WHEN
		int running = mvc.perform(get("/api/runs/{id}", id)).andReturn().getResponse().getStatus();
		int downloadRunning = mvc.perform(get("/api/runs/{id}/datum.json", id)).andReturn().getResponse()
				.getStatus();
		int cancel = mvc.perform(delete("/api/runs/{id}", id)).andReturn().getResponse().getStatus();
		int unknown = mvc.perform(get("/api/runs/{id}", "nope")).andReturn().getResponse().getStatus();
		int invalid = mvc.perform(post("/api/runs").contentType(MediaType.APPLICATION_JSON).content("""
				{"config":{"filters":[]},"datum":[{"i":{"a":1}}]}""")).andReturn().getResponse()
				.getStatus();

		// THEN
		// @formatter:off
		then(running)
			.as("Result of a running run is a conflict")
			.isEqualTo(409)
			;
		then(downloadRunning)
			.as("Download of a running run is a conflict")
			.isEqualTo(409)
			;
		then(cancel)
			.as("Cancel has no content")
			.isEqualTo(204)
			;
		then(unknown)
			.as("Unknown run not found")
			.isEqualTo(404)
			;
		then(invalid)
			.as("Invalid request is a bad request")
			.isEqualTo(400)
			;
		// @formatter:on
	}

}
