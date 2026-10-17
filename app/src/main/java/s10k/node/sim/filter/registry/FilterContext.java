/* ==================================================================
 * FilterContext.java - 18 Oct 2026 8:56:20 am
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

import java.time.Clock;
import java.util.List;
import net.solarnetwork.node.dao.TransientSettingDao;
import net.solarnetwork.node.reactor.InstructionExecutionService;
import net.solarnetwork.node.reactor.ReactorService;
import net.solarnetwork.node.service.DatumMetadataService;
import net.solarnetwork.node.service.DatumQueue;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.OptionalService;

/**
 * The simulated SolarNode services available to a {@link FilterFactory} when
 * creating a filter.
 *
 * <p>
 * Services that every filter uses, like the datum and operational modes
 * services, are injected after the filter is created, so are not part of this
 * API.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public interface FilterContext {

	/**
	 * Get the simulated clock, which follows the timestamp of the datum being
	 * processed.
	 *
	 * @return the clock
	 */
	Clock clock();

	/**
	 * Get the simulated Datum Queue, for filters that generate datum.
	 *
	 * @return the Datum Queue
	 */
	OptionalService<DatumQueue> datumQueue();

	/**
	 * Get the datum metadata service.
	 *
	 * @return the service
	 */
	OptionalService<DatumMetadataService> datumMetadataService();

	/**
	 * Get the instruction execution service.
	 *
	 * @return the service
	 */
	OptionalService<InstructionExecutionService> instructionExecutionService();

	/**
	 * Get the reactor service.
	 *
	 * @return the service
	 */
	OptionalService<ReactorService> reactorService();

	/**
	 * Get the transient setting DAO.
	 *
	 * @return the DAO
	 */
	TransientSettingDao transientSettingDao();

	/**
	 * Get all the filters of the simulation, for filter chains to find their
	 * members in.
	 *
	 * <p>
	 * The list is filled in after all filters are created.
	 * </p>
	 *
	 * @return the filters
	 */
	List<DatumFilterService> filterServices();

}
