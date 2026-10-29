/* ==================================================================
 * SimulationRequest.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * A request to run a simulation.
 *
 * @param config
 *        the filter configuration
 * @param entryPoint
 *        the {@code id} of the filter to apply to each datum, or {@code null}
 *        to use the configuration's main chain
 * @param datum
 *        the input datum, as JSON objects in the SolarNode datum format, for
 *        example
 *        {@code {"created":"2025-10-01 00:00:00Z","sourceId":"/meter/1","i":{"watts":1}}}
 * @param datumSources
 *        the datum sources the simulation polls for more input datum, or
 *        {@code null}
 * @param history
 *        datum to pre-load into the datum history, or {@code null}
 * @param environment
 *        the starting state of the simulated node, or {@code null}
 * @param options
 *        simulation options, or {@code null} for the defaults
 * @author matt
 * @version 1.1
 */
public record SimulationRequest(SimConfig config, @Nullable String entryPoint,
		@Nullable List<JsonNode> datum, @Nullable List<DatumSourceConfig> datumSources,
		@Nullable SimulationHistory history, @Nullable SimulationEnvironment environment,
		@Nullable SimulationOptions options) {

	/**
	 * Constructor, without datum sources.
	 *
	 * @param config
	 *        the filter configuration
	 * @param entryPoint
	 *        the {@code id} of the filter to apply to each datum, or
	 *        {@code null}
	 * @param datum
	 *        the input datum
	 * @param history
	 *        datum to pre-load into the datum history, or {@code null}
	 * @param environment
	 *        the starting state of the simulated node, or {@code null}
	 * @param options
	 *        simulation options, or {@code null} for the defaults
	 */
	public SimulationRequest(SimConfig config, @Nullable String entryPoint,
			@Nullable List<JsonNode> datum, @Nullable SimulationHistory history,
			@Nullable SimulationEnvironment environment, @Nullable SimulationOptions options) {
		this(config, entryPoint, datum, null, history, environment, options);
	}

}
