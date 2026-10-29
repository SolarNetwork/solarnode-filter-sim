/* ==================================================================
 * SimulationEngine.java - 18 Oct 2026 8:56:20 am
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

import java.util.List;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import net.solarnetwork.service.ExpressionService;

/**
 * Runs simulations of SolarNode datum filter configurations.
 *
 * <p>
 * Each simulation creates its own filters and simulated services, so
 * simulations do not share state.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public class SimulationEngine {

	private final FilterTypeRegistry registry;
	private final DatumSourceTypeRegistry datumSourceTypes;
	private final ExpressionService expressionService;
	private final List<String> allowedCommands;

	/**
	 * Constructor, allowing only the external commands each request allows.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param expressionService
	 *        the expression service filters use
	 */
	public SimulationEngine(FilterTypeRegistry registry, ExpressionService expressionService) {
		this(registry, expressionService, List.of());
	}

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param expressionService
	 *        the expression service filters use
	 * @param allowedCommands
	 *        the paths of the external commands filters may run in every
	 *        simulation, in addition to those a request allows
	 */
	public SimulationEngine(FilterTypeRegistry registry, ExpressionService expressionService,
			List<String> allowedCommands) {
		this(registry, DatumSourceTypeRegistry.standard(), expressionService, allowedCommands);
	}

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param datumSourceTypes
	 *        the datum source type registry
	 * @param expressionService
	 *        the expression service filters use
	 * @param allowedCommands
	 *        the paths of the external commands filters may run in every
	 *        simulation, in addition to those a request allows
	 */
	public SimulationEngine(FilterTypeRegistry registry, DatumSourceTypeRegistry datumSourceTypes,
			ExpressionService expressionService, List<String> allowedCommands) {
		super();
		this.registry = registry;
		this.datumSourceTypes = datumSourceTypes;
		this.expressionService = expressionService;
		this.allowedCommands = List.copyOf(allowedCommands);
	}

	/**
	 * Run a simulation.
	 *
	 * @param request
	 *        the request
	 * @return the result
	 * @throws SimulationException
	 *         if the request is not valid
	 */
	public SimulationResult simulate(SimulationRequest request) {
		if ( request.config() == null ) {
			throw new SimulationException("The filter configuration is required.");
		}
		// a snapshot, so reloading custom filters during the run does not change its filter types
		return new SimulationRun(registry.snapshot(), datumSourceTypes, expressionService,
				allowedCommands, request).run();
	}

}
