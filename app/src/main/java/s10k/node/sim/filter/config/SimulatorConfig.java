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

import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import s10k.node.sim.filter.aot.SimulatorRuntimeHints;
import s10k.node.sim.filter.capture.CaptureService;
import s10k.node.sim.filter.csv.SettingsCsvService;
import s10k.node.sim.filter.custom.CustomFilterLoader;
import s10k.node.sim.filter.custom.CustomFilterService;
import s10k.node.sim.filter.form.SettingsFormService;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.run.RunService;
import s10k.node.sim.filter.sim.SimulationEngine;
import s10k.node.sim.filter.sim.SimulationLimits;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simulator configuration.
 *
 * @author matt
 * @version 1.1
 */
@Configuration(proxyBeanMethods = false)
@ImportRuntimeHints(SimulatorRuntimeHints.class)
public class SimulatorConfig {

	/**
	 * The custom filters, from the SolarNode bundle jars and bundle project
	 * directories of the {@code sim.filters.path} property, which can be
	 * reloaded.
	 *
	 * @param path
	 *        the jars, bundle project directories, and directories of them,
	 *        from the {@code sim.filters.path} property
	 * @param expressionService
	 *        the expression service
	 * @return the custom filters service
	 */
	@Bean
	public CustomFilterService customFilterService(@Value("${sim.filters.path:}") List<String> path,
			SpelExpressionService expressionService) {
		final List<String> standard = StandardFilterTypes.definitions().stream()
				.map(FilterTypeDefinition::factoryUid).toList();
		return new CustomFilterService(new CustomFilterLoader(expressionService, standard), path);
	}

	/**
	 * The registry of supported filter types: the standard ones, plus the
	 * custom ones, and the filter types that are not available.
	 *
	 * @param customFilterService
	 *        the custom filters service, which updates the registry when the
	 *        custom filters are reloaded
	 * @return the registry
	 */
	@Bean
	public FilterTypeRegistry filterTypeRegistry(CustomFilterService customFilterService) {
		return customFilterService.registry();
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
	 * The datum source type registry.
	 *
	 * @return the registry
	 */
	@Bean
	public DatumSourceTypeRegistry datumSourceTypeRegistry() {
		return DatumSourceTypeRegistry.standard();
	}

	/**
	 * The simulation engine.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param datumSourceTypes
	 *        the datum source type registry
	 * @param expressionService
	 *        the expression service
	 * @param allowedCommands
	 *        the paths of the external commands filters may run in every
	 *        simulation, from the {@code sim.commands.allowed} property, in
	 *        addition to those each request allows
	 * @param maxDatum
	 *        the most datum a run may process, from the {@code sim.max-datum}
	 *        property
	 * @param maxRunTime
	 *        the longest a run may take, from the {@code sim.max-run-time}
	 *        property
	 * @return the engine
	 */
	@Bean
	public SimulationEngine simulationEngine(FilterTypeRegistry registry,
			DatumSourceTypeRegistry datumSourceTypes, SpelExpressionService expressionService,
			@Value("${sim.commands.allowed:}") List<String> allowedCommands,
			@Value("${sim.max-datum:100000}") int maxDatum,
			@Value("${sim.max-run-time:10m}") Duration maxRunTime) {
		return new SimulationEngine(registry, datumSourceTypes, expressionService, allowedCommands,
				new SimulationLimits(maxDatum, maxRunTime));
	}

	/**
	 * The run service, which runs simulations in the background and keeps their
	 * results.
	 *
	 * @param engine
	 *        the simulation engine
	 * @param mapper
	 *        the web API's JSON mapper
	 * @return the service
	 */
	@Bean(destroyMethod = "close")
	public RunService runService(SimulationEngine engine, JsonMapper mapper) {
		return new RunService(engine, mapper);
	}

	/**
	 * The filter and datum source settings form service.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param datumSourceTypeRegistry
	 *        the datum source type registry
	 * @param expressionService
	 *        the expression service
	 * @return the service
	 */
	@Bean
	public SettingsFormService settingsFormService(FilterTypeRegistry registry,
			DatumSourceTypeRegistry datumSourceTypeRegistry, SpelExpressionService expressionService) {
		return new SettingsFormService(registry, datumSourceTypeRegistry, expressionService);
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
