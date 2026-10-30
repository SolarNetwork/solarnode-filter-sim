/* ==================================================================
 * RunResult.java - 29 Oct 2026 8:30:00 pm
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

import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.util.RawValue;

/**
 * The result of a simulation run kept on the server.
 *
 * <p>
 * A run of up to {@code 200} datum has every datum's trace, like a
 * {@link SimulationResult}. A larger run has only the first traces, plus an
 * {@code index} of every datum and the {@code filterStats} the canvas shows,
 * and its other traces are loaded when needed.
 * </p>
 *
 * @param id
 *        the run ID
 * @param datum
 *        the datum traces, as JSON: every datum's, or only the first ones' when
 *        there is an {@code index}
 * @param instructionRuns
 *        the stored instructions the Instruction Execution Job ran
 * @param messages
 *        warnings and other messages about the simulation
 * @param finalState
 *        the state of the simulated node when the simulation finished
 * @param stats
 *        simulation statistics
 * @param index
 *        for a large run, every processed datum, in processing order
 * @param filterStats
 *        for a large run, the datum each filter processed, by filter id
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RunResult(String id, List<RawValue> datum, List<InstructionRun> instructionRuns,
		List<SimulationMessage> messages, SimulationState finalState, SimulationStats stats,
		@Nullable List<DatumIndexEntry> index, @Nullable Map<String, FilterRunStats> filterStats) {

}
