/* ==================================================================
 * SimulatorConfig.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import s10k.node.sim.filter.aot.SimulatorRuntimeHints;
import s10k.node.sim.filter.capture.CaptureService;
import s10k.node.sim.filter.csv.SettingsCsvService;
import s10k.node.sim.filter.form.SettingsFormService;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationEngine;
import net.solarnetwork.common.expr.spel.SpelExpressionService;

/**
 * Simulator configuration.
 *
 * @author matt
 * @version 1.0
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(SimulatorRuntimeHints.class)
public class SimulatorConfig {

	/**
	 * The registry of supported filter types.
	 *
	 * @return the registry
	 */
	@Bean
	public FilterTypeRegistry filterTypeRegistry() {
		return new FilterTypeRegistry(StandardFilterTypes.definitions());
	}

	/**
	 * The expression service filters use: SolarNode's Spring Expression
	 * Language service.
	 *
	 * @return the service
	 */
	@Bean
	public SpelExpressionService spelExpressionService() {
		return new SpelExpressionService();
	}

	/**
	 * The simulation engine.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param expressionService
	 *        the expression service
	 * @param allowedCommands
	 *        the paths of the external commands filters may run in every
	 *        simulation, from the {@code sim.commands.allowed} property, in
	 *        addition to those each request allows
	 * @return the engine
	 */
	@Bean
	public SimulationEngine simulationEngine(FilterTypeRegistry registry,
			SpelExpressionService expressionService,
			@Value("${sim.commands.allowed:}") List<String> allowedCommands) {
		return new SimulationEngine(registry, expressionService, allowedCommands);
	}

	/**
	 * The filter settings form service.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param expressionService
	 *        the expression service
	 * @return the service
	 */
	@Bean
	public SettingsFormService settingsFormService(FilterTypeRegistry registry,
			SpelExpressionService expressionService) {
		return new SettingsFormService(registry, expressionService);
	}

	/**
	 * The settings CSV import and export service.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param settingsFormService
	 *        the settings form service
	 * @return the service
	 */
	@Bean
	public SettingsCsvService settingsCsvService(FilterTypeRegistry registry,
			SettingsFormService settingsFormService) {
		return new SettingsCsvService(registry, settingsFormService);
	}

	/**
	 * The datum capture service, which records datum from real nodes.
	 *
	 * @return the service
	 */
	@Bean
	public CaptureService captureService() {
		return new CaptureService();
	}

}
