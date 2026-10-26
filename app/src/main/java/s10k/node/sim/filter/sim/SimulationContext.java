/* ==================================================================
 * SimulationContext.java - 18 Oct 2026 8:56:20 am
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

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.util.AntPathMatcher;
import s10k.node.sim.filter.registry.FilterContext;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.node.dao.DefaultTransientSettingDao;
import net.solarnetwork.node.dao.TransientSettingDao;
import net.solarnetwork.node.control.conductor.ControlConductor;
import net.solarnetwork.node.reactor.InstructionExecutionService;
import net.solarnetwork.node.reactor.ReactorService;
import net.solarnetwork.node.runtime.DefaultDatumService;
import net.solarnetwork.node.runtime.DefaultOperationalModesService;
import net.solarnetwork.node.service.DatumMetadataService;
import net.solarnetwork.node.service.DatumQueue;
import net.solarnetwork.node.service.TariffScheduleProvider;
import net.solarnetwork.node.service.support.SettingsPlaceholderService;
import net.solarnetwork.node.service.support.BaseDatumFilterSupport;
import net.solarnetwork.node.service.support.BaseIdentifiable;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ExpressionService;
import net.solarnetwork.service.OptionalService;
import net.solarnetwork.service.OptionalService.OptionalFilterableService;
import net.solarnetwork.service.StaticOptionalService;
import net.solarnetwork.service.StaticOptionalServiceCollection;
import net.solarnetwork.service.support.BasicIdentifiable;

/**
 * The simulated SolarNode services for one simulation run.
 *
 * <p>
 * SolarNode's own implementations are used where they work outside of OSGi: the
 * datum service (datum history), the operational modes service, and the
 * transient setting DAO. In-memory implementations replace the services that
 * need a database, hardware, or SolarNetwork.
 * </p>
 *
 * <p>
 * The context also has a simple service registry, in place of the OSGi one. It
 * holds the Node Metadata Service, the simulated controls, the Control
 * Conductors, the User Metadata Services, and the filters, in that order.
 * {@link SimServiceTracker} finds services in it.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public final class SimulationContext implements FilterContext {

	/**
	 * The properties {@link #injectServices(DatumFilterService, MessageSource)}
	 * sets, with the class a filter must extend for each one to be set.
	 *
	 * <p>
	 * A custom filter's blueprint wiring of these properties is not needed, as
	 * the simulated services replace it.
	 * </p>
	 */
	// @formatter:off
	public static final Map<String, Class<?>> INJECTED_PROPERTIES = Map.of(
			"messageSource", BasicIdentifiable.class,
			"expressionServices", BaseIdentifiable.class,
			"localStateDao", BaseIdentifiable.class,
			"metadataService", BaseIdentifiable.class,
			"placeholderService", BaseIdentifiable.class,
			"locationService", BaseIdentifiable.class,
			"datumService", BaseDatumFilterSupport.class,
			"opModesService", BaseDatumFilterSupport.class,
			"tariffScheduleProviders", BaseDatumFilterSupport.class);
	// @formatter:on

	private static final com.fasterxml.jackson.databind.ObjectMapper NODE_OBJECT_MAPPER = net.solarnetwork.codec.JsonUtils
			.newDatumObjectMapper();

	private final SimulatedClock clock;
	private final SimLocalStateDao localStateDao;
	private final SimSettingDao settingDao;
	private final ConcurrentMap<String, Long> opModes;
	private final DefaultOperationalModesService opModesService;
	private final SimDatumMetadataService datumMetadataService;
	private final DefaultDatumService datumService;
	private final SimNodeControlService controlService;
	private final SimInstructionService instructionService;
	private final DefaultTransientSettingDao transientSettingDao;
	private final SimulatedDatumQueue datumQueue;
	private final SimTaskScheduler taskScheduler;
	private final SettingsPlaceholderService placeholderService;
	private final SimMetricDao metricDao;
	private final SimLocationService locationService;
	private final SimCommandExecutor commandExecutor;
	private @Nullable Supplier<@Nullable String> taskOwner;
	private final SimMetadataService nodeMetadataService;
	private final List<Object> services = new ArrayList<>(16);
	private final StaticOptionalServiceCollection<ExpressionService> expressionServices;
	private final SimServiceTracker<TariffScheduleProvider> tariffScheduleProviders;
	private final List<DatumFilterService> filterServices = new ArrayList<>(16);

	/**
	 * Constructor, allowing no external commands.
	 *
	 * @param startDate
	 *        the initial time of the simulated clock
	 * @param expressionService
	 *        the expression service
	 * @param datumSink
	 *        the sink for datum that filters offer to the Datum Queue
	 * @param nodeMetadata
	 *        the metadata of the Node Metadata Service
	 */
	public SimulationContext(Instant startDate, ExpressionService expressionService,
			SimulatedDatumQueue.Sink datumSink, GeneralDatumMetadata nodeMetadata) {
		this(startDate, expressionService, datumSink, nodeMetadata, List.of());
	}

	/**
	 * Constructor.
	 *
	 * @param startDate
	 *        the initial time of the simulated clock
	 * @param expressionService
	 *        the expression service
	 * @param datumSink
	 *        the sink for datum that filters offer to the Datum Queue
	 * @param nodeMetadata
	 *        the metadata of the Node Metadata Service
	 * @param allowedCommands
	 *        the paths of the external commands filters may run
	 */
	public SimulationContext(Instant startDate, ExpressionService expressionService,
			SimulatedDatumQueue.Sink datumSink, GeneralDatumMetadata nodeMetadata,
			Collection<String> allowedCommands) {
		super();
		this.clock = new SimulatedClock(startDate);
		this.localStateDao = new SimLocalStateDao(clock);
		this.settingDao = new SimSettingDao(clock);
		this.opModes = new ConcurrentHashMap<>(8, 0.9f, 2);
		// on the simulated clock, so modes expire in simulated time
		this.opModesService = new DefaultOperationalModesService(clock, opModes,
				new ConcurrentHashMap<>(4), new StaticOptionalService<>(settingDao), null);
		this.datumMetadataService = new SimDatumMetadataService();

		// the path matcher configuration from the net.solarnetwork.node blueprint
		final AntPathMatcher pathMatcher = new AntPathMatcher();
		pathMatcher.setCachePatterns(true);
		pathMatcher.setCaseSensitive(false);
		this.datumService = new DefaultDatumService(pathMatcher, NODE_OBJECT_MAPPER,
				new StaticOptionalService<>(datumMetadataService));

		this.controlService = new SimNodeControlService(clock);
		this.instructionService = new SimInstructionService(clock, controlService);
		this.transientSettingDao = new DefaultTransientSettingDao();
		this.datumQueue = new SimulatedDatumQueue(datumSink);
		// the node's own placeholder service, without its cache, so placeholders
		// that filters register are seen at once
		this.placeholderService = new SettingsPlaceholderService(
				new StaticOptionalService<>(settingDao));
		this.placeholderService.setCacheSeconds(0);
		this.taskScheduler = new SimTaskScheduler(clock, () -> {
			final Supplier<@Nullable String> owner = taskOwner;
			return (owner != null ? owner.get() : null);
		});
		this.nodeMetadataService = new SimMetadataService(SimMetadataService.NODE_METADATA_SERVICE_UID,
				nodeMetadata);
		registerService(nodeMetadataService);
		registerService(controlService);
		this.expressionServices = new StaticOptionalServiceCollection<>(List.of(expressionService));
		this.tariffScheduleProviders = new SimServiceTracker<>(TariffScheduleProvider.class, services,
				true);
		this.metricDao = new SimMetricDao();
		this.locationService = new SimLocationService();
		this.commandExecutor = new SimCommandExecutor(allowedCommands);
	}

	/**
	 * Register a service, like a SolarNode component that publishes itself as
	 * an OSGi service.
	 *
	 * @param service
	 *        the service
	 */
	public void registerService(Object service) {
		services.add(service);
	}

	/**
	 * Create a Control Conductor with the simulated clock and the services its
	 * blueprint gives it.
	 *
	 * <p>
	 * The conductor still needs its settings, and to be added with
	 * {@link #addConductor(ControlConductor)}.
	 * </p>
	 *
	 * @return the conductor
	 */
	public ControlConductor createConductor() {
		final ControlConductor result = new ControlConductor(clock, reactorService(),
				instructionExecutionService());
		result.setExpressionServices(expressionServices);
		result.setLocalStateDao(new StaticOptionalService<>(localStateDao));
		result.setPlaceholderService(new StaticOptionalService<>(placeholderService));
		result.setDatumService(new StaticOptionalService<>(datumService));
		result.setOpModesService(new StaticOptionalService<>(opModesService));
		return result;
	}

	/**
	 * Add a Control Conductor, as a service and an instruction handler.
	 *
	 * @param conductor
	 *        the conductor
	 */
	public void addConductor(ControlConductor conductor) {
		registerService(conductor);
		instructionService.addConductor(conductor);
	}

	/**
	 * Inject the services every filter uses, like the SolarNode blueprints do.
	 *
	 * @param filter
	 *        the filter
	 * @param messageSource
	 *        the filter message source
	 */
	public void injectServices(DatumFilterService filter, MessageSource messageSource) {
		// keep INJECTED_PROPERTIES up to date with these
		if ( filter instanceof BasicIdentifiable f ) {
			f.setMessageSource(messageSource);
		}
		if ( filter instanceof BaseIdentifiable f ) {
			f.setExpressionServices(expressionServices);
			f.setLocalStateDao(new StaticOptionalService<>(localStateDao));
			// the standard filters' (function=node) MetadataService, for expressions
			f.setMetadataService(new StaticOptionalService<>(nodeMetadataService));
			f.setPlaceholderService(new StaticOptionalService<>(placeholderService));
			f.setLocationService(new StaticOptionalService<>(locationService));
		}
		if ( filter instanceof BaseDatumFilterSupport f ) {
			f.setDatumService(new StaticOptionalService<>(datumService));
			f.setOpModesService(opModesService);
			f.setTariffScheduleProviders(tariffScheduleProviders);
		}
	}

	@Override
	public Clock clock() {
		return clock;
	}

	/**
	 * Get the simulated clock.
	 *
	 * @return the clock
	 */
	public SimulatedClock simulatedClock() {
		return clock;
	}

	@Override
	public OptionalService<DatumQueue> datumQueue() {
		return new StaticOptionalService<>(datumQueue);
	}

	/**
	 * Get the simulated Datum Queue.
	 *
	 * @return the Datum Queue
	 */
	public SimulatedDatumQueue simulatedDatumQueue() {
		return datumQueue;
	}

	@Override
	public OptionalService<DatumMetadataService> datumMetadataService() {
		return new StaticOptionalService<>(datumMetadataService);
	}

	@Override
	public OptionalService<InstructionExecutionService> instructionExecutionService() {
		return new StaticOptionalService<>(instructionService);
	}

	@Override
	public OptionalService<ReactorService> reactorService() {
		return new StaticOptionalService<>(instructionService);
	}

	/**
	 * Get the setting DAO, which holds placeholders.
	 *
	 * @return the DAO
	 */
	public SimSettingDao settingDao() {
		return settingDao;
	}

	/**
	 * Get the datum metadata service.
	 *
	 * @return the service
	 */
	public SimDatumMetadataService simulatedDatumMetadataService() {
		return datumMetadataService;
	}

	@Override
	public SimTaskScheduler taskScheduler() {
		return taskScheduler;
	}

	/**
	 * Set what gives the {@code id} of the filter that schedules a task.
	 *
	 * @param taskOwner
	 *        the task owner supplier
	 */
	public void setTaskOwner(Supplier<@Nullable String> taskOwner) {
		this.taskOwner = taskOwner;
	}

	@Override
	public TransientSettingDao transientSettingDao() {
		return transientSettingDao;
	}

	@Override
	public SimMetricDao metricDao() {
		return metricDao;
	}

	@Override
	public SimCommandExecutor commandExecutor() {
		return commandExecutor;
	}

	@Override
	public com.fasterxml.jackson.databind.ObjectMapper nodeObjectMapper() {
		return NODE_OBJECT_MAPPER;
	}

	/**
	 * Get the Location Service, which has the node location.
	 *
	 * @return the service
	 */
	public SimLocationService locationService() {
		return locationService;
	}

	@Override
	public List<DatumFilterService> filterServices() {
		return filterServices;
	}

	@Override
	public <T> List<T> services(Class<T> serviceClass) {
		final List<T> result = new ArrayList<>(4);
		for ( T service : new SimServiceTracker<>(serviceClass, services, true).services() ) {
			result.add(service);
		}
		return result;
	}

	@Override
	public <T> OptionalFilterableService<T> serviceTracker(Class<T> serviceClass,
			boolean ignoreEmptyPropertyFilterValues) {
		return new SimServiceTracker<>(serviceClass, services, ignoreEmptyPropertyFilterValues);
	}

	/**
	 * Get the Local State DAO.
	 *
	 * @return the DAO
	 */
	public SimLocalStateDao localStateDao() {
		return localStateDao;
	}

	/**
	 * Get the active operational modes, as a map of mode names to expiration
	 * dates (as epoch milliseconds, or
	 * {@link DefaultOperationalModesService#NO_EXPIRATION}).
	 *
	 * @return the active modes
	 */
	public ConcurrentMap<String, Long> opModes() {
		return opModes;
	}

	/**
	 * Get the operational modes service.
	 *
	 * @return the service
	 */
	public DefaultOperationalModesService opModesService() {
		return opModesService;
	}

	/**
	 * Get the datum service, which holds the datum history.
	 *
	 * @return the service
	 */
	public DefaultDatumService datumService() {
		return datumService;
	}

	/**
	 * Get the simulated controls.
	 *
	 * @return the controls
	 */
	public SimNodeControlService controlService() {
		return controlService;
	}

	/**
	 * Get the instruction service.
	 *
	 * @return the service
	 */
	public SimInstructionService instructionService() {
		return instructionService;
	}

}
