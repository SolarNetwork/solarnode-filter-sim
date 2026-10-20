/* ==================================================================
 * TariffScheduleCheck.java - 18 Oct 2026 7:12:40 pm
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

import static net.solarnetwork.domain.tariff.SimpleTemporalRangesTariffEvaluator.DEFAULT_EVALUATOR;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.SimulationMessage;
import net.solarnetwork.domain.tariff.TariffSchedule;
import net.solarnetwork.domain.tariff.TariffUtils;
import net.solarnetwork.node.datum.filter.tariff.TariffDatumFilterService;
import net.solarnetwork.node.service.MetadataService;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.Identifiable;
import net.solarnetwork.service.OptionalService.OptionalFilterableService;

/**
 * A check of the Tariff filter's tariff schedule.
 *
 * <p>
 * Without a tariff schedule the Tariff filter changes no datum, and SolarNode
 * only logs why. This check finds the reason: there is no metadata service with
 * the configured Service Name, the service has no metadata at the configured
 * path, or the metadata there is not a valid schedule.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class TariffScheduleCheck implements FilterCheck {

	private static final String NO_SCHEDULE = "The Tariff filter has no tariff schedule";

	@Override
	public @Nullable String check(DatumFilterService filter, FilterContext context) {
		if ( !(filter instanceof TariffDatumFilterService f) ) {
			return null;
		}
		final TariffSchedule schedule = f.tariffSchedule();
		if ( schedule != null ) {
			return (schedule.rules().isEmpty()
					? "The Tariff filter's schedule has no rules, so the filter changes no datum."
					: null);
		}

		// find the metadata service the way the filter does
		final String uid = f.getMetadataServiceUid();
		final OptionalFilterableService<MetadataService> tracker = context
				.serviceTracker(MetadataService.class, false);
		tracker.setPropertyFilter(Identifiable.UID_PROPERTY, uid);
		final MetadataService service = tracker.service();
		if ( service == null ) {
			return NO_SCHEDULE
					+ ": no metadata service has the Service Name \"%s\". The metadata services are: %s."
							.formatted(uid, context.services(MetadataService.class).stream()
									.map(MetadataService::getUid).filter(Objects::nonNull)
									.map(SimulationMessage::quoted).collect(Collectors.joining(", ")));
		}

		final String path = f.getTariffMetadataPath();
		final Object data = service.metadataAtPath(path);
		if ( data == null ) {
			return NO_SCHEDULE + ": %s has no metadata at %s."
					.formatted(SimulationMessage.quoted(service.getUid()), path);
		}
		try {
			if ( TariffUtils.parseCsvTemporalRangeSchedule(f.getLocale(), f.isPreserveRateCase(),
					f.isFirstMatchOnly(), DEFAULT_EVALUATOR, data) == null ) {
				if ( data instanceof List<?> ) {
					return NO_SCHEDULE
							+ ": the array at %s in %s needs to be an array of rows, each an array of values, starting with a header row of at least 5 columns."
									.formatted(path, service.getUid());
				}
				return NO_SCHEDULE
						+ ": the metadata at %s in %s is a JSON %s, but a tariff schedule must be CSV text or an array of arrays."
								.formatted(path, service.getUid(), jsonType(data));
			}
		} catch ( IOException | RuntimeException e ) {
			return NO_SCHEDULE
					+ ": the metadata at %s in %s is not a valid tariff schedule: %s".formatted(path,
							service.getUid(), e.getMessage() != null ? e.getMessage() : e.toString());
		}
		return NO_SCHEDULE + ".";
	}

	private static String jsonType(Object o) {
		if ( o instanceof Map<?, ?> ) {
			return "object";
		} else if ( o instanceof Collection<?> || o.getClass().isArray() ) {
			return "array";
		} else if ( o instanceof Number ) {
			return "number";
		} else if ( o instanceof Boolean ) {
			return "boolean";
		}
		return "value";
	}

}
