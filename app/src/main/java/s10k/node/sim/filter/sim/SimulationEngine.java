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

import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
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
 * @version 1.0
 */
public class SimulationEngine {

	private final FilterTypeRegistry registry;
	private final ExpressionService expressionService;

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param expressionService
	 *        the expression service filters use
	 */
	public SimulationEngine(FilterTypeRegistry registry, ExpressionService expressionService) {
		super();
		this.registry = registry;
		this.expressionService = expressionService;
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
		return new SimulationRun(registry, expressionService, request).run();
	}

}
