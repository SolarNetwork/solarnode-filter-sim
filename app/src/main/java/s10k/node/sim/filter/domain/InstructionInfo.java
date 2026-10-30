/* ==================================================================
 * InstructionInfo.java - 18 Oct 2026 8:56:20 am
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
import java.util.Map;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An instruction issued by a filter, a Control Conductor, or the simulated
 * Instruction Execution Job.
 *
 * <p>
 * Each instruction is recorded once in a step (or instruction run), with its
 * latest state, even when it is stored, executed, and stored again.
 * </p>
 *
 * @param number
 *        the instruction's number in the simulation, from {@code 1}; SolarNode
 *        local instruction IDs differ between runs, so the simulation numbers
 *        instructions itself
 * @param id
 *        the SolarNode instruction ID, which a {@code CancelInstruction}
 *        instruction's {@code id} parameter refers to, or {@code null}
 * @param topic
 *        the instruction topic, for example {@code SetControlParameter}
 * @param parameters
 *        the instruction parameters
 * @param state
 *        the simulated instruction state, or {@code null} if no handler handled
 *        the instruction, like a {@code SetControlParameter} instruction for a
 *        control the simulation does not have
 * @param parent
 *        the number of the instruction that issued this one, or {@code null}
 * @param executeAt
 *        for an instruction stored for the Instruction Execution Job to run
 *        later, its execution date, or {@code null}
 * @param message
 *        the status {@code message} result parameter, or {@code null}
 * @param code
 *        the status error {@code code} result parameter, or {@code null}
 * @param conductor
 *        for a Control Conductor task's {@code Signal} instruction, the
 *        conductor's Service Name, or {@code null}
 * @param task
 *        for a Control Conductor task's {@code Signal} instruction, the task
 *        number, from {@code 1}, or {@code null}
 * @param handler
 *        the component that gave the instruction its status when it was
 *        executed, or {@code null} if none did, as when no handler handled it
 *        or the topic is one that completes without a handler
 * @author matt
 * @version 1.2
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InstructionInfo(int number, @Nullable Long id, String topic,
		Map<String, String> parameters, @Nullable String state, @Nullable Integer parent,
		@Nullable Instant executeAt, @Nullable String message, @Nullable String code,
		@Nullable String conductor, @Nullable Integer task, @Nullable InstructionHandlerInfo handler) {

}
