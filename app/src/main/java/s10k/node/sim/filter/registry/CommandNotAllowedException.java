/* ==================================================================
 * CommandNotAllowedException.java - 20 Oct 2026 3:52:01 pm
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

package s10k.node.sim.filter.registry;

import java.io.Serial;

/**
 * A filter asked to run an external command that is not allowed.
 *
 * <p>
 * The simulation reports the command on the filter's step, so the log events
 * this causes are not reported again.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class CommandNotAllowedException extends IllegalStateException {

	@Serial
	private static final long serialVersionUID = 1L;

	/**
	 * Constructor.
	 *
	 * @param message
	 *        the message
	 */
	public CommandNotAllowedException(String message) {
		super(message);
	}

}
