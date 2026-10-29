/* ==================================================================
 * RunController.java - 29 Oct 2026 9:30:00 pm
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

package s10k.node.sim.filter.web;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.RunInfo;
import s10k.node.sim.filter.domain.RunProgress;
import s10k.node.sim.filter.domain.RunResult;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.run.DownloadDatum;
import s10k.node.sim.filter.run.RunService;
import tools.jackson.databind.util.RawValue;

/**
 * Web API for simulation runs that run in the background.
 *
 * @author matt
 * @version 1.0
 */
@RestController
@RequestMapping("/api/runs")
@RegisterReflectionForBinding({ DatumTrace.class, DownloadDatum.class, RunProgress.class })
public class RunController {

	/** The date format in download file names. */
	private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'")
			.withZone(ZoneOffset.UTC);

	private final RunService service;

	/**
	 * Constructor.
	 *
	 * @param service
	 *        the run service
	 */
	public RunController(RunService service) {
		super();
		this.service = service;
	}

	/**
	 * Start a run.
	 *
	 * @param request
	 *        the simulation request
	 * @return the run
	 */
	@PostMapping
	public RunInfo start(@RequestBody SimulationRequest request) {
		return service.start(request);
	}

	/**
	 * Get the event stream of a run's progress.
	 *
	 * @param id
	 *        the run ID
	 * @return the event stream
	 * @see RunService#events(String)
	 */
	@GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events(@PathVariable String id) {
		return service.events(id);
	}

	/**
	 * Get a run's progress.
	 *
	 * @param id
	 *        the run ID
	 * @return the progress
	 */
	@GetMapping("/{id}/progress")
	public RunProgress progress(@PathVariable String id) {
		return service.progress(id);
	}

	/**
	 * Get a run's result.
	 *
	 * @param id
	 *        the run ID
	 * @return the result
	 * @see RunService#result(String)
	 */
	@GetMapping("/{id}")
	public RunResult result(@PathVariable String id) {
		return service.result(id);
	}

	/**
	 * Get the traces of some of a run's datum.
	 *
	 * @param id
	 *        the run ID
	 * @param seq
	 *        the datum sequence numbers, comma-delimited or repeated
	 * @return the traces
	 */
	@GetMapping("/{id}/datum")
	public List<RawValue> traces(@PathVariable String id, @RequestParam List<Integer> seq) {
		return service.traces(id, seq);
	}

	/**
	 * Download a run's datum, as a JSON array in the format of the Datum input.
	 *
	 * @param id
	 *        the run ID
	 * @param kind
	 *        {@code output} (the default) for the datum the filters output, to
	 *        be persisted, or {@code input} for each datum as it entered the
	 *        Datum Queue
	 * @return the datum
	 */
	@GetMapping("/{id}/datum.json")
	public ResponseEntity<StreamingResponseBody> download(@PathVariable String id,
			@RequestParam(required = false) @Nullable String kind) {
		final boolean output = !"input".equalsIgnoreCase(kind);
		service.checkFinished(id);
		final Instant start = service.startDate(id);
		final String name = "solarnode-filter-sim-%s%s.json".formatted(output ? "output" : "input",
				start != null ? "-" + FILE_DATE.format(start) : "");
		final StreamingResponseBody body = out -> service.writeDatum(id, output, out);
		// @formatter:off
		return ResponseEntity.ok()
				.contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(name).build().toString())
				.body(body);
		// @formatter:on
	}

	/**
	 * Cancel a run.
	 *
	 * @param id
	 *        the run ID
	 * @return no content
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> cancel(@PathVariable String id) {
		service.cancel(id);
		return ResponseEntity.noContent().build();
	}

}
