/* ==================================================================
 * StandardDatumSourceTypes.java - 29 Oct 2026 7:00:00 pm
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

import static s10k.node.sim.filter.registry.DatumSourceTypeDefinition.SETTING_PREFIX;
import static s10k.node.sim.filter.registry.MessageSources.messageSource;
import static s10k.node.sim.filter.registry.MessageSources.prefixed;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import net.solarnetwork.domain.tariff.TariffSchedule;
import net.solarnetwork.domain.tariff.TemporalTariffEvaluator;
import net.solarnetwork.node.datum.battery.mock.MockBatteryDatumDataSource;
import net.solarnetwork.node.datum.energymeter.mock.MockEnergyMeterDatumSource;
import net.solarnetwork.node.datum.fixed.FixedDatumDataSource;
import net.solarnetwork.node.datum.tou.TouDatumDataSource;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.service.DatumDataSource;
import net.solarnetwork.node.service.MetadataService;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.node.service.TariffScheduleProvider;
import net.solarnetwork.service.StaticOptionalService;

/**
 * The supported SolarNode datum source types.
 *
 * <p>
 * Each definition is a Java port of the datum source's blueprint wiring, with
 * the simulated clock in place of the system clock.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class StandardDatumSourceTypes {

	/** The Fixed Data datum source factory UID. */
	public static final String FIXED = "net.solarnetwork.node.datum.fixed";

	/** The Mock Battery datum source factory UID. */
	public static final String MOCK_BATTERY = "net.solarnetwork.node.datum.battery.mock";

	/** The Mock Energy Meter datum source factory UID. */
	public static final String MOCK_ENERGY_METER = "net.solarnetwork.node.datum.energymeter.mock";

	/** The TOU datum source factory UID. */
	public static final String TOU = "net.solarnetwork.node.datum.tou";

	/**
	 * The poll schedule the blueprints give each datum source: every minute.
	 */
	public static final String DEFAULT_SCHEDULE = "0 * * * * ?";

	/** The message key prefix the blueprints give the datum sources. */
	private static final String JOB_MESSAGE_PREFIX = "datumDataSource.";

	private static final String DATUM_DATA_SOURCE_SUPPORT = "net.solarnetwork.node.service.support.DatumDataSourceSupport";
	private static final String BASIC_IDENTIFIABLE = "net.solarnetwork.service.support.BasicIdentifiable";

	private StandardDatumSourceTypes() {
		// not available
	}

	/**
	 * Get the datum source type definitions.
	 *
	 * @return the definitions
	 */
	public static List<DatumSourceTypeDefinition> definitions() {
		StandardFilterTypes.initNodeHome();
		final List<DatumSourceTypeDefinition> result = new ArrayList<>(4);

		final MessageSource fixedMessages = messageSource(
				"net.solarnetwork.node.datum.fixed.FixedDatumDataSource", DATUM_DATA_SOURCE_SUPPORT,
				BASIC_IDENTIFIABLE);
		result.add(new DatumSourceTypeDefinition(FIXED, "Fixed Data", FixedDatumDataSource.class,
				fixedMessages, (ctx, _) -> {
					FixedDatumDataSource s = new FixedDatumDataSource(ctx.clock());
					s.setMessageSource(prefixed(JOB_MESSAGE_PREFIX, fixedMessages));
					s.setPlaceholderService(new StaticOptionalService<>(ctx.placeholderService()));
					s.setMetadataService(ctx.serviceTracker(MetadataService.class, false));
					return s;
				}, DEFAULT_SCHEDULE, List.of(DatumDataSource.class),
				s -> ((FixedDatumDataSource) s).getSourceId(),
				StandardDatumSourceTypes::fixedTimeZoneLimitation, null));

		final MessageSource batteryMessages = messageSource(
				"net.solarnetwork.node.datum.battery.mock.MockBatteryDatumDataSource",
				DATUM_DATA_SOURCE_SUPPORT, BASIC_IDENTIFIABLE);
		result.add(new DatumSourceTypeDefinition(MOCK_BATTERY, "Mock Battery",
				MockBatteryDatumDataSource.class, batteryMessages, (ctx, _) -> {
					MockBatteryDatumDataSource s = new MockBatteryDatumDataSource(ctx.clock());
					s.setMessageSource(prefixed(JOB_MESSAGE_PREFIX, batteryMessages));
					s.setPlaceholderService(new StaticOptionalService<>(ctx.placeholderService()));
					s.setEventAdmin(new StaticOptionalService<>(ctx.eventAdmin()));
					return s;
				}, DEFAULT_SCHEDULE,
				List.of(DatumDataSource.class, NodeControlProvider.class, InstructionHandler.class),
				s -> ((MockBatteryDatumDataSource) s).getSourceId(), null, null));

		final MessageSource meterMessages = messageSource(
				"net.solarnetwork.node.datum.energymeter.mock.MockEnergyMeterDatumSource",
				DATUM_DATA_SOURCE_SUPPORT, BASIC_IDENTIFIABLE);
		result.add(new DatumSourceTypeDefinition(MOCK_ENERGY_METER, "EnergyMeter Mock",
				MockEnergyMeterDatumSource.class, meterMessages, (ctx, rng) -> {
					MockEnergyMeterDatumSource s = new MockEnergyMeterDatumSource(ctx.clock(), rng);
					s.setMessageSource(prefixed(JOB_MESSAGE_PREFIX, meterMessages));
					s.setPlaceholderService(new StaticOptionalService<>(ctx.placeholderService()));
					s.setMetadataService(ctx.serviceTracker(MetadataService.class, false));
					return s;
				}, DEFAULT_SCHEDULE, List.of(DatumDataSource.class),
				s -> ((MockEnergyMeterDatumSource) s).getSourceId(),
				settings -> timeZoneLimitation(settings, "touMetadataPath",
						"The Mock Energy Meter's TOU schedule"),
				null));

		final MessageSource touMessages = messageSource("propertyConfigsItem.",
				messageSource("net.solarnetwork.node.datum.tou.TouPropertyConfig",
						"net.solarnetwork.domain.datum.NumberDatumSamplePropertyConfig"),
				"net.solarnetwork.node.datum.tou.TouDatumDataSource", DATUM_DATA_SOURCE_SUPPORT,
				"net.solarnetwork.node.service.support.TariffScheduleUtils", BASIC_IDENTIFIABLE);
		result.add(new DatumSourceTypeDefinition(TOU, "TOU Datum Source", TouDatumDataSource.class,
				touMessages, (ctx, _) -> {
					TouDatumDataSource s = new TouDatumDataSource(ctx.clock(),
							ctx.serviceTracker(MetadataService.class, false),
							ctx.serviceTracker(TemporalTariffEvaluator.class, false));
					s.setMessageSource(prefixed(JOB_MESSAGE_PREFIX, touMessages));
					s.setPlaceholderService(new StaticOptionalService<>(ctx.placeholderService()));
					return s;
				}, DEFAULT_SCHEDULE, List.of(DatumDataSource.class, TariffScheduleProvider.class),
				s -> ((TouDatumDataSource) s).getSourceId(),
				settings -> timeZoneLimitation(settings, "metadataPath", "The TOU datum source"),
				StandardDatumSourceTypes::touCheck));

		return result;
	}

	private static @Nullable String setting(Map<String, String> settings, String key) {
		final String value = settings.get(SETTING_PREFIX + key);
		return (value != null && !value.isBlank() ? value : null);
	}

	private static @Nullable String timeZoneLimitation(Map<String, String> settings, String pathKey,
			String subject) {
		if ( setting(settings, pathKey) == null ) {
			return null;
		}
		return ("%s matches times to its tariff schedule in the node's time zone. The simulator "
				+ "uses its own time zone instead, which is %s. To simulate a node in another time "
				+ "zone, start the simulator with the TZ environment variable set to that zone.")
						.formatted(subject, ZoneId.systemDefault().getId());
	}

	private static @Nullable String fixedTimeZoneLimitation(Map<String, String> settings) {
		if ( setting(settings, "touZoneId") != null ) {
			return null;
		}
		return timeZoneLimitation(settings, "touMetadataPath", "The Fixed Data source's TOU rates");
	}

	private static @Nullable String touCheck(DatumDataSource source, DatumSourceContext context) {
		if ( !(source instanceof TouDatumDataSource s) ) {
			return null;
		}
		final String path = s.getMetadataPath();
		if ( path == null || path.isBlank() ) {
			return "The TOU datum source has no Metadata Path, so it has no tariff schedule, and returns no datum.";
		}
		final TariffSchedule schedule = s.tariffSchedule();
		if ( schedule != null ) {
			return (schedule.rules().isEmpty()
					? "The TOU datum source's tariff schedule has no rules, so it returns no datum."
					: null);
		}
		return TariffScheduleCheck.scheduleProblem("The TOU datum source has no tariff schedule",
				s.getMetadataServiceUid(), path, s.getLocale(), s.isPreserveRateCase(),
				s.isFirstMatchOnly(), context);
	}

}
