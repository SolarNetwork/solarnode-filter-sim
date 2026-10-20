/* ==================================================================
 * SimPvlibPoaDatumFilterService.java - 20 Oct 2026 3:34:23 pm
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
import com.fasterxml.jackson.databind.ObjectMapper;
import net.solarnetwork.node.datum.pvlib.PvlibPoaDatumFilterService;
import net.solarnetwork.node.service.DatumMetadataService;
import net.solarnetwork.node.service.MetadataService;
import net.solarnetwork.service.OptionalService;
import net.solarnetwork.service.OptionalService.OptionalFilterableService;

/**
 * The POA Calculator filter, running its command through a
 * {@link CommandExecutor}.
 *
 * <p>
 * The executor only runs allowed commands, and records each one. A command
 * timeout of {@code 0} (wait forever) or over
 * {@link #MAX_COMMAND_TIMEOUT_SECONDS} is limited to that maximum, so a command
 * cannot hold up a simulation for long.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimPvlibPoaDatumFilterService extends PvlibPoaDatumFilterService {

	/** The longest time the simulator waits for a command, in seconds. */
	public static final int MAX_COMMAND_TIMEOUT_SECONDS = 60;

	private final CommandExecutor commandExecutor;

	/**
	 * Constructor.
	 *
	 * @param objectMapper
	 *        the object mapper to parse command results with
	 * @param datumMetadataService
	 *        the datum metadata service
	 * @param characteristicsMetadataService
	 *        the PV characteristics metadata service tracker
	 * @param commandExecutor
	 *        the command executor
	 */
	public SimPvlibPoaDatumFilterService(ObjectMapper objectMapper,
			OptionalService<DatumMetadataService> datumMetadataService,
			OptionalFilterableService<MetadataService> characteristicsMetadataService,
			CommandExecutor commandExecutor) {
		super(objectMapper, datumMetadataService, characteristicsMetadataService);
		this.commandExecutor = commandExecutor;
	}

	@Override
	protected Map<String, ?> executeCommand(List<String> cmd) {
		final int timeout = getCommandTimeoutSeconds();
		if ( timeout < 1 || timeout > MAX_COMMAND_TIMEOUT_SECONDS ) {
			setCommandTimeoutSeconds(MAX_COMMAND_TIMEOUT_SECONDS);
		}
		return commandExecutor.execute(cmd, super::executeCommand);
	}

}
