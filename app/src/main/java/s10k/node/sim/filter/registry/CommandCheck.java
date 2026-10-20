/* ==================================================================
 * CommandCheck.java - 20 Oct 2026 3:34:23 pm
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

import org.jspecify.annotations.Nullable;
import net.solarnetwork.node.datum.pvlib.PvlibPoaDatumFilterService;
import net.solarnetwork.service.DatumFilterService;

/**
 * Check that the POA Calculator filter's command is allowed to run.
 *
 * @author matt
 * @version 1.0
 */
public class CommandCheck implements FilterCheck {

	@Override
	public @Nullable String check(DatumFilterService filter, FilterContext context) {
		if ( !(filter instanceof PvlibPoaDatumFilterService f) ) {
			return null;
		}
		final String command = f.getCommand();
		if ( command == null || context.commandExecutor().isAllowed(command) ) {
			return null;
		}
		return ("The command %s is not in the Allowed Commands list, so the simulator will not run "
				+ "it. Add it to the list to run it.").formatted(command);
	}

}
