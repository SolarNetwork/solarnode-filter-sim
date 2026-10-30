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

import java.util.ArrayList;
import java.util.List;
import s10k.node.sim.filter.domain.DatumTrace;
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
 * @version 1.2
 */
public class SimulationEngine {

	private final FilterTypeRegistry registry;
	private final DatumSourceTypeRegistry datumSourceTypes;
	private final ExpressionService expressionService;
	private final List<String> allowedCommands;
	private final SimulationLimits limits;

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
		this(registry, datumSourceTypes, expressionService, allowedCommands, SimulationLimits.DEFAULTS);
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
	 * @param limits
	 *        the limits on every simulation
	 */
	public SimulationEngine(FilterTypeRegistry registry, DatumSourceTypeRegistry datumSourceTypes,
			ExpressionService expressionService, List<String> allowedCommands, SimulationLimits limits) {
		super();
		this.registry = registry;
		this.datumSourceTypes = datumSourceTypes;
		this.expressionService = expressionService;
		this.allowedCommands = List.copyOf(allowedCommands);
		this.limits = limits;
	}

	/**
	 * Get the limits on every simulation.
	 *
	 * @return the limits
	 */
	public SimulationLimits limits() {
		return limits;
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
		final List<DatumTrace> traces = new ArrayList<>(16);
		final SimulationResult result = prepare(request).run(traces::add);
		return new SimulationResult(traces, result.instructionRuns(), result.messages(),
				result.finalState(), result.stats());
	}

	/**
	 * Prepare a simulation, checking the request, to run it later, perhaps on
	 * another thread.
	 *
	 * @param request
	 *        the request
	 * @return the simulation, to run once
	 * @throws SimulationException
	 *         if the request is not valid
	 */
	public PreparedSimulation prepare(SimulationRequest request) {
		if ( request.config() == null ) {
			throw new SimulationException("The filter configuration is required.");
		}
		// snapshots, so reloading custom filters during the run does not change its types
		final SimulationRun run = new SimulationRun(registry.snapshot(), datumSourceTypes.snapshot(),
				expressionService, allowedCommands, limits, request);
		return run::run;
	}

	/**
	 * A simulation ready to run.
	 */
	@FunctionalInterface
	public interface PreparedSimulation {

		/**
		 * Run the simulation.
		 *
		 * @param observer
		 *        gets each datum's trace once it is processed, and can cancel
		 *        the run
		 * @return the result, without the datum traces, which only the observer
		 *         gets
		 * @throws SimulationException
		 *         if the configuration is not valid
		 */
		SimulationResult run(SimulationObserver observer);

	}

}
