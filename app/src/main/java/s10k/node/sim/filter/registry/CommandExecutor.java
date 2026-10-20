/* ==================================================================
 * CommandExecutor.java - 20 Oct 2026 3:34:23 pm
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

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * API for running the external commands that filters ask to run.
 *
 * <p>
 * The executor decides whether a command may run, and records what happened.
 * The filter's own code runs the command.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public interface CommandExecutor {

	/**
	 * Run a command, if it is allowed.
	 *
	 * @param command
	 *        the command and its arguments
	 * @param runner
	 *        the filter's function that runs the command and returns its result
	 * @return the command result
	 * @throws RuntimeException
	 *         if the command is not allowed, or the runner fails
	 */
	Map<String, ?> execute(List<String> command, Function<List<String>, Map<String, ?>> runner);

	/**
	 * Test if a command may run.
	 *
	 * @param command
	 *        the command path
	 * @return {@code true} if the command may run
	 */
	boolean isAllowed(String command);

}
