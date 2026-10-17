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
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
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
import net.solarnetwork.node.datum.filter.virt.VirtualMeterDatumFilterService;
import net.solarnetwork.node.service.support.DatumFilterChainService;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ServiceLifecycleObserver;
import s10k.node.sim.filter.domain.FilterCapability;

/**
 * The user datum filters from the SolarNode
 * {@code net.solarnetwork.node.datum.filter.standard} bundle, plus the filter
 * chain from the core {@code net.solarnetwork.node} bundle.
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
	 * Create the standard filter type definitions.
	 *
	 * @return the definitions
	 */
	public static List<FilterTypeDefinition> definitions() {
		final List<FilterTypeDefinition> result = new ArrayList<>(16);

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
				ctx -> new DownsampleDatumFilterService(ctx.clock()), null, null, Set.of(),
				settings -> hasValue(settings, "sampleDurationSecs")
						? "Downsampling by Sample Duration follows the system clock in this version "
								+ "of SolarNode, not datum timestamps, so samples are not grouped "
								+ "the way they would be on a node. Downsampling by Sample Count "
								+ "is not affected."
						: null));

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
				_ -> new OperationalModeDatumFilterService(), null, null, Set.of(), null));

		result.add(def(JOIN, "Join Filter", JoinDatumFilterService.class,
				messageSource(STD + "JoinDatumFilterService", BASE_FILTER, BASIC_IDENTIFIABLE),
				ctx -> new JoinDatumFilterService(ctx.clock(), ctx.datumQueue()),
				StandardFilterTypes::startup, StandardFilterTypes::shutdown,
				EnumSet.of(FilterCapability.GeneratesDatum, FilterCapability.ScheduledTasks),
				settings -> hasValue(settings, "coalesceTimeoutMillis")
						? "The Join filter's Coalesce Timeout runs as a scheduled task, which the "
								+ "simulator does not run yet, so joined datum are only generated "
								+ "when the Coalesce Threshold is reached."
						: null));

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
				ctx -> new TimeoutDatumFilterService(ctx.clock(), ctx.datumQueue()),
				StandardFilterTypes::startup, StandardFilterTypes::shutdown,
				EnumSet.of(FilterCapability.GeneratesDatum, FilterCapability.ScheduledTasks),
				_ -> "The Timeout filter generates datum from a scheduled task, which the "
						+ "simulator does not run yet, so it never generates datum."));

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

		return result;
	}

	private static FilterTypeDefinition def(String factoryUid, String displayName,
			Class<? extends DatumFilterService> filterClass, MessageSource messageSource,
			FilterFactory factory, @Nullable Consumer<DatumFilterService> init,
			@Nullable Consumer<DatumFilterService> destroy, Set<FilterCapability> capabilities,
			@Nullable Function<Map<String, String>, @Nullable String> limitations) {
		return new FilterTypeDefinition(factoryUid, displayName, filterClass, messageSource, factory,
				init, destroy, capabilities, limitations);
	}

	private static boolean hasValue(Map<String, String> settings, String key) {
		String v = settings.get(key);
		return (v != null && !v.isBlank() && !"0".equals(v.trim()));
	}

}
