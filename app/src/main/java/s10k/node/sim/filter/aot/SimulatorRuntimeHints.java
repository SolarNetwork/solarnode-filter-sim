/* ==================================================================
 * SimulatorRuntimeHints.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.aot;

import java.beans.PropertyDescriptor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.beans.BeanUtils;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import net.solarnetwork.domain.datum.DatumDateFunctions;
import net.solarnetwork.domain.datum.DatumExpressionRoot;
import net.solarnetwork.domain.datum.DatumMathFunctions;
import net.solarnetwork.domain.datum.DatumMetadataOperations;
import net.solarnetwork.domain.datum.DatumSamplesExpressionRoot;
import net.solarnetwork.domain.datum.DatumStringFunctions;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.domain.tariff.CompositeTariff;
import net.solarnetwork.domain.tariff.SimpleTariffRate;
import net.solarnetwork.domain.tariff.SimpleTemporalTariff;
import net.solarnetwork.domain.tariff.SimpleTemporalTariffSchedule;
import net.solarnetwork.domain.tariff.Tariff;
import net.solarnetwork.domain.tariff.TariffSchedule;
import net.solarnetwork.domain.tariff.TemporalRangeSetsTariff;
import net.solarnetwork.domain.tariff.TemporalTariff;
import net.solarnetwork.node.datum.filter.virt.VirtualMeterExpressionRoot;
import net.solarnetwork.node.datum.filter.virt.VirtualMeterExpressionRootImpl;
import net.solarnetwork.node.domain.ExpressionRoot;
import net.solarnetwork.node.domain.LocalStateOperations;
import net.solarnetwork.node.domain.TariffScheduleProvidersOperations;

/**
 * Native image runtime hints for the SolarNode filters.
 *
 * <ul>
 * <li>Filters and their nested configuration classes are configured through a
 * Spring {@code BeanWrapper}, which uses reflection on their bean
 * properties.</li>
 * <li>Filter message sources load {@code .properties} resources.</li>
 * <li>Expressions call methods on their root objects, and on common JDK types,
 * through reflection.</li>
 * </ul>
 *
 * @author matt
 * @version 1.0
 */
public class SimulatorRuntimeHints implements RuntimeHintsRegistrar {

	/**
	 * The types expressions call methods on, including the metadata and the
	 * tariff schedules that expressions can get: a schedule's {@code rules()}
	 * are its parsed tariffs, and {@code resolveTariff()} returns a
	 * {@code SimpleTemporalTariff}. Expressions call methods on the concrete
	 * collection types too, like the maps, lists, and tag sets in metadata.
	 */
	public static final List<Class<?>> EXPRESSION_TYPES = List.of(ExpressionRoot.class,
			DatumSamplesExpressionRoot.class, DatumExpressionRoot.class, DatumMathFunctions.class,
			DatumDateFunctions.class, DatumStringFunctions.class, DatumMetadataOperations.class,
			GeneralDatumMetadata.class, LocalStateOperations.class,
			TariffScheduleProvidersOperations.class, TariffSchedule.class,
			SimpleTemporalTariffSchedule.class, Tariff.class, TemporalRangeSetsTariff.class,
			CompositeTariff.class, TemporalTariff.class, SimpleTemporalTariff.class, Tariff.Rate.class,
			SimpleTariffRate.class, VirtualMeterExpressionRoot.class,
			VirtualMeterExpressionRootImpl.class, BigDecimal.class, BigInteger.class, Number.class,
			Integer.class, Long.class, Float.class, Double.class, String.class, Math.class,
			Instant.class, Duration.class, ZonedDateTime.class, OffsetDateTime.class,
			LocalDateTime.class, LocalDate.class, LocalTime.class, Collection.class, List.class,
			Set.class, Map.class, ArrayList.class, HashMap.class, LinkedHashMap.class, HashSet.class,
			LinkedHashSet.class);

	@Override
	public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
		hints.resources().registerPattern("net/solarnetwork/**/*.properties");

		final Set<Class<?>> registered = new HashSet<>(64);
		for ( FilterTypeDefinition def : StandardFilterTypes.definitions() ) {
			registerBeanType(hints, def.filterClass(), registered);
		}

		for ( Class<?> type : EXPRESSION_TYPES ) {
			hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS);
		}
	}

	/**
	 * Register a bean type, and the SolarNetwork types of its properties.
	 *
	 * @param hints
	 *        the hints
	 * @param type
	 *        the type to register
	 * @param registered
	 *        the types already registered
	 */
	private static void registerBeanType(RuntimeHints hints, Class<?> type, Set<Class<?>> registered) {
		if ( !registered.add(type) ) {
			return;
		}
		hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
				MemberCategory.INVOKE_PUBLIC_METHODS);
		for ( PropertyDescriptor prop : BeanUtils.getPropertyDescriptors(type) ) {
			Class<?> propType = prop.getPropertyType();
			if ( propType == null ) {
				continue;
			}
			if ( propType.isArray() ) {
				hints.reflection().registerType(propType);
				propType = propType.getComponentType();
			}
			if ( propType.getName().startsWith("net.solarnetwork.") && !propType.isInterface() ) {
				registerBeanType(hints, propType, registered);
			}
		}
	}

}
