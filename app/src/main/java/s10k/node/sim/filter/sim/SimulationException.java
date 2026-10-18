/* ==================================================================
 * SimulationException.java - 18 Oct 2026 8:56:20 am
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

import java.io.Serial;
import java.util.List;
import s10k.node.sim.filter.domain.SimulationMessage;

/**
 * A problem with a request that prevents it from being handled, such as invalid
 * datum JSON for a simulation.
 *
 * <p>
 * The exception can list the individual problems, for example every filter
 * instance ID that prevents a settings export.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimulationException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = -1186421547226001537L;

	/** The problems. */
	private final transient List<SimulationMessage> problems;

	/**
	 * Constructor.
	 *
	 * @param message
	 *        the message
	 */
	public SimulationException(String message) {
		super(message);
		this.problems = List.of();
	}

	/**
	 * Constructor.
	 *
	 * @param message
	 *        the message
	 * @param problems
	 *        the individual problems
	 */
	public SimulationException(String message, List<SimulationMessage> problems) {
		super(message);
		this.problems = List.copyOf(problems);
	}

	/**
	 * Constructor.
	 *
	 * @param message
	 *        the message
	 * @param cause
	 *        the cause
	 */
	public SimulationException(String message, Throwable cause) {
		super(message, cause);
		this.problems = List.of();
	}

	/**
	 * Get the individual problems.
	 *
	 * @return the problems, never {@code null}
	 */
	public List<SimulationMessage> problems() {
		return problems;
	}

}
