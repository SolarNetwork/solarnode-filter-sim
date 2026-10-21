/* ==================================================================
 * ApiExceptionHandler.java - 18 Oct 2026 8:56:20 am
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

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import s10k.node.sim.filter.capture.CaptureException;
import s10k.node.sim.filter.sim.SimulationException;

/**
 * Turn web API exceptions into problem details responses.
 *
 * @author matt
 * @version 1.0
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	/**
	 * Handle an invalid API request.
	 *
	 * <p>
	 * When the exception lists individual problems, they are included as a
	 * {@code problems} array of messages.
	 * </p>
	 *
	 * @param e
	 *        the exception
	 * @return the problem details, with a {@code 400} status
	 */
	@ExceptionHandler(SimulationException.class)
	public ProblemDetail simulationException(SimulationException e) {
		ProblemDetail result = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		result.setTitle("Invalid request");
		if ( !e.problems().isEmpty() ) {
			result.setProperty("problems", e.problems());
		}
		return result;
	}

	/**
	 * Handle a datum capture that could not start.
	 *
	 * @param e
	 *        the exception
	 * @return the problem details, with a {@code 400} status
	 */
	@ExceptionHandler(CaptureException.class)
	public ProblemDetail captureException(CaptureException e) {
		ProblemDetail result = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
		result.setTitle("Capture not started");
		return result;
	}

}
