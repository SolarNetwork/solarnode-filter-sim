/* ==================================================================
 * InstructionHandlerInfo.java - 29 Oct 2026 2:10:00 pm
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

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The simulated node component that gave an instruction its status.
 *
 * @param type
 *        the kind of component
 * @param name
 *        the component's name: the control ID of a control or Operational Mode
 *        Switch, the {@code id} of a datum source configuration, or the Service
 *        Name of a Control Conductor or metadata service; or {@code null} for a
 *        component that has none
 * @author matt
 * @version 1.2
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InstructionHandlerInfo(Type type, @Nullable String name) {

	/**
	 * The kinds of instruction handler, in the order they are asked to handle
	 * an instruction.
	 */
	public enum Type {

		/**
		 * The reactor, which handles {@code CancelInstruction} before any other
		 * handler.
		 */
		Reactor,

		/**
		 * The operational modes service, which handles
		 * {@code EnableOperationalModes} and {@code DisableOperationalModes}.
		 */
		OperationalModes,

		/**
		 * A metadata service, which handles a {@code Signal} with a parameter
		 * named its signal name.
		 */
		Metadata,

		/** A simulated control, which handles {@code SetControlParameter}. */
		Control,

		/**
		 * An Operational Mode Switch, which handles {@code SetControlParameter}
		 * by enabling or disabling its operational mode.
		 */
		OperationalModeSwitch,

		/**
		 * A datum source that handles instructions, such as a Mock Battery,
		 * which handles {@code SetControlParameter} for its controls.
		 */
		DatumSource,

		/**
		 * A Control Conductor, which handles {@code OrchestrateControls} and
		 * its tasks' {@code Signal}s.
		 */
		Conductor,

	}

}
