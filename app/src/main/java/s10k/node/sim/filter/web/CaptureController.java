/* ==================================================================
 * CaptureController.java - 21 Oct 2026 2:30:00 pm
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

import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import s10k.node.sim.filter.capture.CaptureService;
import s10k.node.sim.filter.capture.CaptureSession;
import s10k.node.sim.filter.domain.CaptureInfo;
import s10k.node.sim.filter.domain.CaptureRequest;

/**
 * Web API for capturing datum from a SolarNode.
 *
 * @author matt
 * @version 1.0
 */
@RestController
@RequestMapping("/api/capture")
public class CaptureController {

	private final CaptureService service;

	/**
	 * Constructor.
	 *
	 * @param service
	 *        the capture service
	 */
	public CaptureController(CaptureService service) {
		super();
		this.service = service;
	}

	/**
	 * Start a capture.
	 *
	 * @param request
	 *        the node and how to log in to it
	 * @return the capture
	 */
	@PostMapping
	public CaptureInfo start(@RequestBody CaptureRequest request) {
		return service.start(request);
	}

	/**
	 * Get the event stream of a capture.
	 *
	 * @param id
	 *        the capture ID
	 * @param lastEventId
	 *        the number of the last datum the browser has, which an
	 *        {@code EventSource} sends when it reconnects
	 * @return the event stream
	 * @see CaptureSession#events(String)
	 */
	@GetMapping(path = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events(@PathVariable String id,
			@RequestHeader(name = "Last-Event-ID", required = false) @Nullable String lastEventId) {
		SseEmitter emitter = service.events(id, lastEventId);
		if ( emitter == null ) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such capture.");
		}
		return emitter;
	}

	/**
	 * End a capture.
	 *
	 * @param id
	 *        the capture ID
	 * @return no content, or not found
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> end(@PathVariable String id) {
		return (service.end(id) ? ResponseEntity.noContent() : ResponseEntity.notFound()).build();
	}

}
