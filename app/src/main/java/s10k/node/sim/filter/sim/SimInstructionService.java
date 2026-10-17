/* ==================================================================
 * SimInstructionService.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.sim;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.InstructionInfo;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionExecutionService;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.reactor.InstructionUtils;
import net.solarnetwork.node.reactor.ReactorService;

/**
 * Simulated instruction execution and reactor services, which record the
 * instructions filters issue instead of executing them.
 *
 * <p>
 * Executed instructions complete successfully. Stored instructions are only
 * received.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimInstructionService implements InstructionExecutionService, ReactorService {

	private @Nullable Consumer<InstructionInfo> listener;

	@Override
	public @Nullable InstructionStatus executeInstruction(Instruction instruction) {
		record(instruction, InstructionState.Completed);
		return InstructionUtils.createStatus(instruction, InstructionState.Completed);
	}

	@Override
	public InstructionStatus processInstruction(Instruction instruction) {
		record(instruction, InstructionState.Completed);
		return InstructionUtils.createStatus(instruction, InstructionState.Completed);
	}

	@Override
	public void storeInstruction(Instruction instruction) {
		record(instruction, InstructionState.Received);
	}

	private void record(Instruction instruction, InstructionState state) {
		final Consumer<InstructionInfo> l = this.listener;
		if ( l == null ) {
			return;
		}
		Map<String, String> params = new LinkedHashMap<>(8);
		for ( String name : instruction.getParameterNames() ) {
			params.put(name, instruction.getParameterValue(name));
		}
		final String topic = instruction.getTopic();
		l.accept(new InstructionInfo(topic != null ? topic : "", params, state.name()));
	}

	/**
	 * Set the listener for issued instructions.
	 *
	 * @param listener
	 *        the listener to set, or {@code null}
	 */
	public void setListener(@Nullable Consumer<InstructionInfo> listener) {
		this.listener = listener;
	}

}
