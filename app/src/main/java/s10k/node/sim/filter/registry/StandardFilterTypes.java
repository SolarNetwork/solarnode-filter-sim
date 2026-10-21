/* ==================================================================
 * StandardFilterTypes.java - 18 Oct 2026 8:56:20 am
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

import static s10k.node.sim.filter.registry.MessageSources.messageSource;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import net.solarnetwork.domain.tariff.TemporalTariffEvaluator;
import net.solarnetwork.node.Constants;
import net.solarnetwork.node.datum.filter.control.ControlUpdaterDatumFilterService;
import net.solarnetwork.node.datum.filter.expr.ExpressionDatumFilterService;
import net.solarnetwork.node.datum.filter.instr.InstructorDatumFilterService;
import net.solarnetwork.node.datum.filter.opmode.OperationalModeDatumFilterService;
import net.solarnetwork.node.datum.filter.param.ParameterDatumFilterService;
import net.solarnetwork.node.datum.filter.std.DatumFilterSupport;
import net.solarnetwork.node.datum.filter.std.DownsampleDatumFilterService;
import net.solarnetwork.node.datum.filter.std.JoinDatumFilterService;
import net.solarnetwork.node.datum.filter.std.PropertyDatumFilterService;
import net.solarnetwork.node.datum.filter.std.RoundedTimestampDatumFilterService;
import net.solarnetwork.node.datum.filter.std.SplitDatumFilterService;
import net.solarnetwork.node.datum.filter.std.ThrottlingDatumFilterService;
import net.solarnetwork.node.datum.filter.std.TimeoutDatumFilterService;
import net.solarnetwork.node.datum.filter.std.UnchangedDatumFilterService;
import net.solarnetwork.node.datum.filter.std.UnchangedPropertyDatumFilterService;
import net.solarnetwork.node.datum.filter.tariff.TariffDatumFilterService;
import net.solarnetwork.node.datum.filter.virt.VirtualMeterDatumFilterService;
import net.solarnetwork.node.metrics.harvester.MetricHarvesterDatumFilterService;
import net.solarnetwork.node.service.MetadataService;
import net.solarnetwork.node.service.support.DatumFilterChainService;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ServiceLifecycleObserver;
import s10k.node.sim.filter.domain.FilterCapability;

/**
 * The user datum filters from the SolarNode
 * {@code net.solarnetwork.node.datum.filter.standard} bundle, plus the filter
 * chain from the core {@code net.solarnetwork.node} bundle, the Tariff filter
 * from the {@code net.solarnetwork.node.datum.filter.tariff} bundle, the POA
 * Calculator filter from the {@code net.solarnetwork.node.datum.filter.pvlib}
 * bundle, and the Metric Harvester filter from the
 * {@code net.solarnetwork.node.metrics.harvester} bundle.
 *
 * <p>
 * Each definition is a port of the filter's wiring in its bundle's
 * {@code OSGI-INF/blueprint/module.xml}: factory UID, display name, message
 * source, constructor arguments, filter-specific properties, and init and
 * destroy methods. Check the blueprint when updating the SolarNode bundles.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class StandardFilterTypes {

	/** The Datum Filter Chain factory UID. */
	public static final String CHAIN = FilterTypeDefinition.CHAIN_FACTORY_UID;

	/** The Property Datum Filter factory UID. */
	public static final String PROPERTY = "net.solarnetwork.node.datum.samplefilter.prop";

	/** The Throttle Datum Filter factory UID. */
	public static final String THROTTLE = "net.solarnetwork.node.datum.samplefilter.limiter";

	/** The Downsample Datum Filter factory UID. */
	public static final String DOWNSAMPLE = "net.solarnetwork.node.datum.samplefilter.downsample";

	/** The Virtual Meter Datum Filter factory UID. */
	public static final String VIRTUAL_METER = "net.solarnetwork.node.datum.samplefilter.virtmeter";

	/** The Expression Datum Filter factory UID. */
	public static final String EXPRESSION = "net.solarnetwork.node.datum.samplefilter.expression";

	/** The Operational Mode Datum Filter factory UID. */
	public static final String OP_MODE = "net.solarnetwork.node.datum.samplefilter.opmode";

	/** The Join Datum Filter factory UID. */
	public static final String JOIN = "net.solarnetwork.node.datum.filter.std.join";

	/** The Split Datum Filter factory UID. */
	public static final String SPLIT = "net.solarnetwork.node.datum.filter.std.split";

	/** The Parameter Expression Datum Filter factory UID. */
	public static final String PARAMETER = "net.solarnetwork.node.datum.filter.std.param";

	/** The Unchanged Datum Filter factory UID. */
	public static final String UNCHANGED = "net.solarnetwork.node.datum.filter.std.unchanged";

	/** The Unchanged Property Datum Filter factory UID. */
	public static final String UNCHANGED_PROPERTY = "net.solarnetwork.node.datum.filter.std.unchangedprop";

	/** The Control Updater Datum Filter factory UID. */
	public static final String CONTROL_UPDATER = "net.solarnetwork.node.datum.filter.control.update";

	/** The Timeout Datum Filter factory UID. */
	public static final String TIMEOUT = "net.solarnetwork.node.datum.filter.std.timeout";

	/** The Rounded Timestamp Datum Filter factory UID. */
	public static final String TIMESTAMP = "net.solarnetwork.node.datum.filter.std.timestamp";

	/** The Instructor Datum Filter factory UID. */
	public static final String INSTRUCTOR = "net.solarnetwork.node.datum.filter.instructor";

	/** The Time-based Tariff Datum Filter factory UID. */
	public static final String TARIFF = "net.solarnetwork.node.datum.filter.tariff";

	/** The POA Calculator (pvlib) Datum Filter factory UID. */
	public static final String PVLIB_POA = "net.solarnetwork.node.datum.filter.pvlib.poa";

	/** The Metric Harvester Datum Filter factory UID. */
	public static final String METRIC_HARVESTER = "net.solarnetwork.node.metrics.service.harvester";

	/**
	 * SolarNode user datum filters that the simulator does not support, as
	 * factory UIDs and titles.
	 *
	 * <p>
	 * Imports keep their settings, and simulations pass datum through them
	 * unchanged. The simulator supports every user datum filter in the
	 * SolarNode repository, so this is empty, until a new filter is added
	 * there.
	 * </p>
	 */
	public static final Map<String, String> UNSUPPORTED = Map.of();

	/**
	 * The SolarNode home directory on a node, which the {@code sn.home} system
	 * property gives, unless the simulator is started with another value.
	 *
	 * <p>
	 * The POA Calculator filter's default command is in its {@code bin}
	 * directory, where the {@code sn-pvlib} package installs it.
	 * </p>
	 */
	public static final String NODE_HOME = "/var/lib/solarnode";

	/**
	 * The factory UIDs of the global datum filters in the standard bundle,
	 * which the simulator does not support: the global Property and Throttle
	 * filters.
	 */
	public static final Set<String> GLOBAL = Set.of("net.solarnetwork.node.datum.samplefilter.simple",
			"net.solarnetwork.node.datum.samplefilter.throttle");

	private static final String BASE_FILTER = "net.solarnetwork.node.service.support.BaseDatumFilterSupport";
	private static final String BASIC_IDENTIFIABLE = "net.solarnetwork.service.support.BasicIdentifiable";
	private static final String EXPRESSION_CONFIG = "net.solarnetwork.node.service.support.ExpressionConfig";
	private static final String STD = "net.solarnetwork.node.datum.filter.std.";

	private StandardFilterTypes() {
		// not available
	}

	/** The {@code init} init method. */
	private static void init(DatumFilterService filter) {
		((DatumFilterSupport) filter).init();
	}

	/** The {@code serviceDidStartup} init method. */
	private static void startup(DatumFilterService filter) {
		((ServiceLifecycleObserver) filter).serviceDidStartup();
	}

	/** The {@code serviceDidShutdown} destroy method. */
	private static void shutdown(DatumFilterService filter) {
		((ServiceLifecycleObserver) filter).serviceDidShutdown();
	}

	/**
	 * Set the {@code sn.home} system property to {@link #NODE_HOME}, unless the
	 * simulator was started with a value.
	 *
	 * <p>
	 * This must happen before any filter class reads it, as the POA
	 * Calculator's default command is a constant made from it.
	 * </p>
	 */
	public static void initNodeHome() {
		if ( System.getProperty(Constants.SYSTEM_PROP_NODE_HOME) == null ) {
			System.setProperty(Constants.SYSTEM_PROP_NODE_HOME, NODE_HOME);
		}
	}

	/**
	 * Create the standard filter type definitions.
	 *
	 * @return the definitions
	 */
	public static List<FilterTypeDefinition> definitions() {
		initNodeHome();
		final List<FilterTypeDefinition> result = new ArrayList<>(18);

		result.add(def(CHAIN, "Datum Filter Chain", DatumFilterChainService.class,
				messageSource("net.solarnetwork.node.service.support.UserDatumFilterChainService",
						BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new DatumFilterChainService(CHAIN, ctx.filterServices()), null, null, Set.of(),
				null));

		final MessageSource simpleFilterMessageSource = messageSource(STD + "PropertyDatumFilterService",
				BASE_FILTER, BASIC_IDENTIFIABLE);
		result.add(
				def(PROPERTY, "Property Datum Filter", PropertyDatumFilterService.class,
						messageSource("sampleTransformer.", simpleFilterMessageSource,
								STD + "DatumPropertyTransformService", BASE_FILTER, BASIC_IDENTIFIABLE),
						ctx -> {
							PropertyDatumFilterService f = new PropertyDatumFilterService();
							f.setTransientSettingDao(ctx.transientSettingDao());
							f.setSettingUid(PROPERTY);
							return f;
						}, StandardFilterTypes::init, null, Set.of(), null));

		final MessageSource sourceThrottleMessageSource = messageSource(
				STD + "ThrottlingDatumFilterService", BASE_FILTER, BASIC_IDENTIFIABLE);
		result.add(
				def(THROTTLE, "Throttle Datum Filter", ThrottlingDatumFilterService.class,
						messageSource("sampleTransformer.", sourceThrottleMessageSource,
								STD + "DatumThrottleTransformService", BASE_FILTER, BASIC_IDENTIFIABLE),
						ctx -> {
							ThrottlingDatumFilterService f = new ThrottlingDatumFilterService();
							f.setTransientSettingDao(ctx.transientSettingDao());
							f.setSettingUid(THROTTLE);
							return f;
						}, StandardFilterTypes::init, null, Set.of(), null));

		result.add(def(DOWNSAMPLE, "Downsampler Datum Filter", DownsampleDatumFilterService.class,
				messageSource(STD + "DownsampleDatumFilterService", BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new DownsampleDatumFilterService(ctx.clock()), null, null, Set.of(), null));

		result.add(def(VIRTUAL_METER, "Virtual Meter Filter", VirtualMeterDatumFilterService.class,
				messageSource("net.solarnetwork.node.datum.filter.virt.VirtualMeterDatumFilterService",
						EXPRESSION_CONFIG, BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new VirtualMeterDatumFilterService(ctx.datumMetadataService()), null, null,
				Set.of(), null));

		result.add(def(EXPRESSION, "Expression Filter", ExpressionDatumFilterService.class,
				messageSource("net.solarnetwork.node.datum.filter.expr.ExpressionDatumFilterService",
						EXPRESSION_CONFIG, BASE_FILTER, BASIC_IDENTIFIABLE),
				_ -> new ExpressionDatumFilterService(), null, null, Set.of(), null));

		result.add(def(OP_MODE, "Operational Mode Filter", OperationalModeDatumFilterService.class,
				messageSource(
						"net.solarnetwork.node.datum.filter.opmode.OperationalModeDatumFilterService",
						EXPRESSION_CONFIG, BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new OperationalModeDatumFilterService(ctx.clock()), null, null, Set.of(), null));

		result.add(def(JOIN, "Join Filter", JoinDatumFilterService.class,
				messageSource(STD + "JoinDatumFilterService", BASE_FILTER, BASIC_IDENTIFIABLE), ctx -> {
					JoinDatumFilterService f = new JoinDatumFilterService(ctx.clock(), ctx.datumQueue());
					f.setTaskScheduler(ctx.taskScheduler());
					return f;
				}, StandardFilterTypes::startup, StandardFilterTypes::shutdown,
				EnumSet.of(FilterCapability.GeneratesDatum, FilterCapability.ScheduledTasks), null));

		result.add(def(SPLIT, "Split Filter", SplitDatumFilterService.class,
				messageSource(STD + "SplitDatumFilterService", BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new SplitDatumFilterService(ctx.datumQueue()), null, null,
				EnumSet.of(FilterCapability.GeneratesDatum), null));

		result.add(def(PARAMETER, "Parameter Expression Filter", ParameterDatumFilterService.class,
				messageSource("net.solarnetwork.node.datum.filter.param.ParameterDatumFilterService",
						EXPRESSION_CONFIG, BASE_FILTER, BASIC_IDENTIFIABLE),
				_ -> new ParameterDatumFilterService(), null, null, Set.of(), null));

		result.add(def(UNCHANGED, "Unchanged Filter", UnchangedDatumFilterService.class,
				messageSource(STD + "UnchangedDatumFilterService", BASE_FILTER, BASIC_IDENTIFIABLE),
				_ -> new UnchangedDatumFilterService(), null, null, Set.of(), null));

		result.add(def(UNCHANGED_PROPERTY, "Unchanged Property Filter",
				UnchangedPropertyDatumFilterService.class,
				messageSource(STD + "UnchangedPropertyDatumFilterService", BASE_FILTER,
						BASIC_IDENTIFIABLE),
				_ -> new UnchangedPropertyDatumFilterService(), null, null, Set.of(), null));

		result.add(def(CONTROL_UPDATER, "Control Updater Filter", ControlUpdaterDatumFilterService.class,
				messageSource("controlConfigsItem.",
						messageSource("net.solarnetwork.node.datum.filter.control.ControlConfig"),
						"net.solarnetwork.node.datum.filter.control.ControlUpdaterDatumFilterService",
						BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new ControlUpdaterDatumFilterService(ctx.instructionExecutionService()), null,
				null, EnumSet.of(FilterCapability.Instructions), null));

		result.add(def(TIMEOUT, "Timeout Filter", TimeoutDatumFilterService.class,
				messageSource(STD + "TimeoutDatumFilterService", BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> {
					TimeoutDatumFilterService f = new TimeoutDatumFilterService(ctx.clock(),
							ctx.datumQueue());
					f.setTaskScheduler(ctx.taskScheduler());
					return f;
				}, StandardFilterTypes::startup, StandardFilterTypes::shutdown,
				EnumSet.of(FilterCapability.GeneratesDatum, FilterCapability.ScheduledTasks), null));

		result.add(def(TIMESTAMP, "Timestamp Filter", RoundedTimestampDatumFilterService.class,
				messageSource(STD + "RoundedTimestampDatumFilterService", BASE_FILTER,
						BASIC_IDENTIFIABLE),
				ctx -> new RoundedTimestampDatumFilterService(ctx.clock()), null, null, Set.of(), null));

		final MessageSource instructorConfigMessageSource = messageSource("instructionsItem.",
				messageSource("net.solarnetwork.node.datum.filter.instr.InstructionConfig"),
				"net.solarnetwork.node.datum.filter.instr.InstructorConfig");
		result.add(def(INSTRUCTOR, "Instructor Filter", InstructorDatumFilterService.class,
				messageSource("instructorConfigsItem.", instructorConfigMessageSource,
						"net.solarnetwork.node.datum.filter.instr.InstructorDatumFilterService",
						BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new InstructorDatumFilterService(ctx.clock(), ctx.reactorService(),
						ctx.instructionExecutionService()),
				null, null, EnumSet.of(FilterCapability.Instructions), null));

		// the 4.1.1 blueprint, which also injects the opModesService
		result.add(def(TARIFF, "Time-based Tariff Filter", TariffDatumFilterService.class,
				messageSource("net.solarnetwork.node.datum.filter.tariff.TariffDatumFilterService",
						BASE_FILTER, "net.solarnetwork.node.service.support.TariffScheduleUtils",
						BASIC_IDENTIFIABLE),
				ctx -> new TariffDatumFilterService(ctx.serviceTracker(MetadataService.class, false),
						ctx.serviceTracker(TemporalTariffEvaluator.class, false)),
				null, null, Set.of(), _ -> tariffTimeZoneLimitation())
						.withCheck(new TariffScheduleCheck()));

		result.add(def(PVLIB_POA, "POA Calculator Filter", SimPvlibPoaDatumFilterService.class,
				messageSource("net.solarnetwork.node.datum.pvlib.PvlibPoaDatumFilterService",
						EXPRESSION_CONFIG, BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new SimPvlibPoaDatumFilterService(ctx.nodeObjectMapper(),
						ctx.datumMetadataService(), ctx.serviceTracker(MetadataService.class, false),
						ctx.commandExecutor()),
				null, null, EnumSet.of(FilterCapability.Commands),
				StandardFilterTypes::pvlibTimeoutLimitation).withCheck(new CommandCheck()));

		result.add(def(METRIC_HARVESTER, "Metric Harvester", MetricHarvesterDatumFilterService.class,
				messageSource("propertyConfigsItem.",
						messageSource(
								"net.solarnetwork.node.metrics.harvester.MetricHarvesterPropertyConfig",
								"net.solarnetwork.domain.datum.NumberDatumSamplePropertyConfig"),
						"net.solarnetwork.node.metrics.harvester.MetricHarvesterDatumFilterService",
						EXPRESSION_CONFIG, BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new MetricHarvesterDatumFilterService(ctx.metricDao()), null, null,
				EnumSet.of(FilterCapability.Metrics), null));

		return result;
	}

	private static @Nullable String pvlibTimeoutLimitation(Map<String, String> settings) {
		final String value = settings.get("commandTimeoutSeconds");
		if ( value == null || value.isBlank() ) {
			return null;
		}
		int timeout;
		try {
			timeout = Integer.parseInt(value.trim());
		} catch ( NumberFormatException e ) {
			return null;
		}
		if ( timeout > 0 && timeout <= SimPvlibPoaDatumFilterService.MAX_COMMAND_TIMEOUT_SECONDS ) {
			return null;
		}
		return ("The simulator waits at most %d seconds for the command to finish, so it uses that "
				+ "instead of the Command Timeout.")
						.formatted(SimPvlibPoaDatumFilterService.MAX_COMMAND_TIMEOUT_SECONDS);
	}

	private static String tariffTimeZoneLimitation() {
		return ("The Tariff filter matches datum dates to its schedule in the node's time zone. "
				+ "The simulator uses its own time zone instead, which is %s. To simulate a node "
				+ "in another time zone, start the simulator with the TZ environment variable "
				+ "set to that zone.").formatted(ZoneId.systemDefault().getId());
	}

	private static FilterTypeDefinition def(String factoryUid, String displayName,
			Class<? extends DatumFilterService> filterClass, MessageSource messageSource,
			FilterFactory factory, @Nullable Consumer<DatumFilterService> init,
			@Nullable Consumer<DatumFilterService> destroy, Set<FilterCapability> capabilities,
			@Nullable Function<Map<String, String>, @Nullable String> limitations) {
		return new FilterTypeDefinition(factoryUid, displayName, filterClass, messageSource, factory,
				init, destroy, capabilities, limitations, null, null);
	}

}
