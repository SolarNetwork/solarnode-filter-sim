/* ==================================================================
 * RunProgress.java - 29 Oct 2026 8:30:00 pm
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

package s10k.node.sim.filter.domain;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;

/**
 * The progress of a simulation run.
 *
 * @param state
 *        the run's state
 * @param processed
 *        the datum processed so far
 * @param expected
 *        the datum expected: the input datum plus the datum sources' polls, or
 *        more if filters generated more
 * @param date
 *        the date of the last datum processed, which is how far the simulated
 *        clock has reached
 * @param error
 *        for a failed run, why it failed
 * @param problems
 *        for a failed run, the individual problems, if any
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record RunProgress(State state, int processed, int expected, @Nullable Instant date,
		@Nullable String error, @Nullable List<SimulationMessage> problems) {

	/** The state of a run. */
	public enum State {

		/** The run is waiting to start, or running. */
		Running,

		/** The run finished (or was cancelled), and its result is ready. */
		Done,

		/** The run failed, as its configuration is not valid. */
		Failed,

	}

}
