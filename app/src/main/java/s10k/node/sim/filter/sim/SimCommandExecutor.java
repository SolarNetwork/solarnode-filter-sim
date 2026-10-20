/* ==================================================================
 * SimCommandExecutor.java - 20 Oct 2026 3:35:26 pm
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

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.CommandInfo;
import s10k.node.sim.filter.registry.CommandExecutor;
import s10k.node.sim.filter.registry.CommandNotAllowedException;

/**
 * A {@link CommandExecutor} that runs only allowed commands, and reports each
 * command it is asked to run.
 *
 * <p>
 * A command is allowed when its path is exactly one of the allowed commands.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimCommandExecutor implements CommandExecutor {

	private final Set<String> allowed;
	private @Nullable Consumer<CommandInfo> listener;

	/**
	 * Constructor.
	 *
	 * @param allowed
	 *        the allowed command paths; blank values are ignored
	 */
	public SimCommandExecutor(Collection<String> allowed) {
		super();
		final Set<String> set = new LinkedHashSet<>(allowed.size());
		for ( String cmd : allowed ) {
			if ( cmd != null && !cmd.isBlank() ) {
				set.add(cmd.trim());
			}
		}
		this.allowed = Set.copyOf(set);
	}

	@Override
	public boolean isAllowed(String command) {
		return allowed.contains(command);
	}

	@Override
	public Map<String, ?> execute(List<String> command, Function<List<String>, Map<String, ?>> runner) {
		final List<String> cmd = List.copyOf(command);
		if ( cmd.isEmpty() || !isAllowed(cmd.getFirst()) ) {
			final String msg = ("The command %s is not in the Allowed Commands list, so the simulator "
					+ "did not run it.").formatted(cmd.isEmpty() ? "" : cmd.getFirst());
			report(new CommandInfo(cmd, false, null, msg, 0));
			throw new CommandNotAllowedException(msg);
		}
		final long start = System.nanoTime();
		try {
			final Map<String, ?> result = runner.apply(cmd);
			report(new CommandInfo(cmd, true, FilterTracer.sanitize(result), null,
					(System.nanoTime() - start) / 1000L));
			return result;
		} catch ( RuntimeException e ) {
			report(new CommandInfo(cmd, true, null,
					e.getMessage() != null ? e.getMessage() : e.toString(),
					(System.nanoTime() - start) / 1000L));
			throw e;
		}
	}

	private void report(CommandInfo info) {
		final Consumer<CommandInfo> l = this.listener;
		if ( l != null ) {
			l.accept(info);
		}
	}

	/**
	 * Set the listener for commands.
	 *
	 * @param listener
	 *        the listener to set, or {@code null}
	 */
	public void setListener(@Nullable Consumer<CommandInfo> listener) {
		this.listener = listener;
	}

}
