/* ==================================================================
 * InstructionRun.java - 25 Oct 2026 11:07:40 am
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
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A stored instruction that the simulated Instruction Execution Job ran, such
 * as a Control Conductor task's {@code Signal} instruction, between datum.
 *
 * @param date
 *        when the job ran the instruction
 * @param afterSeq
 *        the sequence number of the last datum processed before the run, or
 *        {@code null} if none was
 * @param instruction
 *        the instruction, with its state after the run
 * @param instructions
 *        the other instructions issued while it ran, like a Control Conductor's
 *        {@code SetControlParameter}
 * @param controls
 *        the control values set while it ran
 * @param generated
 *        the sequence numbers of the datum published while it ran
 * @param opModes
 *        the change to the active operational modes while it ran, or
 *        {@code null} if there was none
 * @param datumSeq
 *        the sequence number of the datum whose filter step issued the
 *        instruction (or the instruction it came from), or {@code null}
 * @param stepId
 *        the ID of that filter step, or {@code null}
 * @param filterId
 *        the {@code id} of that step's filter configuration, or {@code null}
 * @param error
 *        the error the instruction threw, or {@code null}
 * @author matt
 * @version 1.1
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InstructionRun(Instant date, @Nullable Integer afterSeq, InstructionInfo instruction,
		List<InstructionInfo> instructions, List<ControlUpdate> controls, List<Integer> generated,
		@Nullable OpModeChange opModes, @Nullable Integer datumSeq, @Nullable Integer stepId,
		@Nullable String filterId, @Nullable String error) {

}
