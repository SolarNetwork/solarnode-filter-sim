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
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.Month;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.aot.hint.TypeReference;
import org.springframework.beans.BeanUtils;
import s10k.node.sim.filter.domain.CaptureInfo;
import s10k.node.sim.filter.domain.CustomFiltersReload;
import s10k.node.sim.filter.domain.DatumSourceTypeInfo;
import s10k.node.sim.filter.domain.ExternalSettings;
import s10k.node.sim.filter.domain.ExternalSettingsRequest;
import s10k.node.sim.filter.domain.FilterTypeInfo;
import s10k.node.sim.filter.domain.RunInfo;
import s10k.node.sim.filter.domain.RunProgress;
import s10k.node.sim.filter.domain.RunResult;
import s10k.node.sim.filter.domain.SettingsExport;
import s10k.node.sim.filter.domain.SettingsExportRequest;
import s10k.node.sim.filter.domain.SettingsForm;
import s10k.node.sim.filter.domain.SettingsFormRequest;
import s10k.node.sim.filter.domain.SettingsImport;
import s10k.node.sim.filter.domain.SettingsMergeRequest;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.domain.UnavailableFilterTypeInfo;
import s10k.node.sim.filter.run.DownloadDatum;
import s10k.node.sim.filter.registry.DatumSourceTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.StandardDatumSourceTypes;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.web.InfoController;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumDateFunctions;
import net.solarnetwork.domain.datum.DatumExpressionRoot;
import net.solarnetwork.domain.datum.DatumMathFunctions;
import net.solarnetwork.domain.datum.DatumMetadataOperations;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.DatumSamplesExpressionRoot;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.DatumStringFunctions;
import net.solarnetwork.domain.datum.GeneralDatum;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.domain.tariff.CompositeTariff;
import net.solarnetwork.domain.tariff.SimpleTariffRate;
import net.solarnetwork.domain.tariff.SimpleTemporalTariff;
import net.solarnetwork.domain.tariff.SimpleTemporalTariffSchedule;
import net.solarnetwork.domain.tariff.Tariff;
import net.solarnetwork.domain.tariff.TariffSchedule;
import net.solarnetwork.domain.tariff.TemporalRangeSetsTariff;
import net.solarnetwork.domain.tariff.TemporalTariff;
import net.solarnetwork.domain.BasicDeviceInfo;
import net.solarnetwork.domain.DeviceInfo;
import net.solarnetwork.node.control.conductor.ControlConductor;
import net.solarnetwork.node.datum.filter.virt.VirtualMeterExpressionRoot;
import net.solarnetwork.node.datum.filter.virt.VirtualMeterExpressionRootImpl;
import net.solarnetwork.node.reactor.BasicInstruction;
import net.solarnetwork.node.reactor.BasicInstructionStatus;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.domain.ExpressionRoot;
import net.solarnetwork.node.domain.LocalStateOperations;
import net.solarnetwork.node.domain.TariffScheduleProvidersOperations;
import net.solarnetwork.node.domain.datum.SimpleAcEnergyDatum;
import net.solarnetwork.node.domain.datum.SimpleDatum;
import net.solarnetwork.node.domain.datum.SimpleEnergyStorageDatum;
import net.solarnetwork.node.domain.datum.SimpleNodeControlInfoDatum;
import net.solarnetwork.node.datum.opmode.DatumDataSourceOpModeInvoker;
import net.solarnetwork.node.domain.datum.AcEnergyDatum;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.domain.datum.EnergyStorageDatum;
import net.solarnetwork.node.job.DatumDataSourcePollManagedJob;
import net.solarnetwork.node.job.SimpleManagedJob;
import tools.jackson.databind.util.RawValue;

/**
 * Native image runtime hints for the SolarNode filters.
 *
 * <ul>
 * <li>Filters, Control Conductors, datum sources with their jobs, and their
 * nested configuration classes are configured through a Spring
 * {@code BeanWrapper}, which uses reflection on their bean properties.</li>
 * <li>Filter message sources load {@code .properties} resources.</li>
 * <li>A datum source's poll job serializes its device info with Jackson, which
 * uses reflection on its getters.</li>
 * <li>Expressions call methods on their root objects, and on common JDK types,
 * through reflection.</li>
 * </ul>
 *
 * @author matt
 * @version 1.4
 */
public class SimulatorRuntimeHints implements RuntimeHintsRegistrar {

	/**
	 * The types expressions call methods on, including the datum ({@code datum}
	 * is a {@code SimpleDatum}), the metadata, and the tariff schedules that
	 * expressions can get: a schedule's {@code rules()} are its parsed tariffs,
	 * and {@code resolveTariff()} returns a {@code SimpleTemporalTariff}.
	 * Expressions call methods on the concrete collection types too, like the
	 * maps, lists, and tag sets in metadata. The Instructor's response
	 * expressions read the {@code instruction} and {@code instructionResult}
	 * variables, a SolarNode {@code BasicInstruction} and
	 * {@code BasicInstructionStatus}, including the default methods of their
	 * interfaces, like {@code isCompleted()}.
	 */
	public static final List<Class<?>> EXPRESSION_TYPES = List.of(ExpressionRoot.class,
			DatumSamplesExpressionRoot.class, DatumExpressionRoot.class, Datum.class, GeneralDatum.class,
			SimpleDatum.class, SimpleEnergyStorageDatum.class, SimpleAcEnergyDatum.class,
			SimpleNodeControlInfoDatum.class, DatumSamples.class, DatumSamplesOperations.class,
			DatumMathFunctions.class, DatumDateFunctions.class, DatumStringFunctions.class,
			DatumMetadataOperations.class, GeneralDatumMetadata.class, LocalStateOperations.class,
			TariffScheduleProvidersOperations.class, TariffSchedule.class,
			SimpleTemporalTariffSchedule.class, Tariff.class, TemporalRangeSetsTariff.class,
			CompositeTariff.class, TemporalTariff.class, SimpleTemporalTariff.class, Tariff.Rate.class,
			SimpleTariffRate.class, VirtualMeterExpressionRoot.class,
			VirtualMeterExpressionRootImpl.class, BigDecimal.class, BigInteger.class, Number.class,
			Integer.class, Long.class, Float.class, Double.class, String.class, Math.class,
			Instant.class, Duration.class, ZonedDateTime.class, OffsetDateTime.class,
			LocalDateTime.class, LocalDate.class, LocalTime.class, Collection.class, List.class,
			Set.class, Map.class, ArrayList.class, HashMap.class, LinkedHashMap.class, HashSet.class,
			LinkedHashSet.class, TreeMap.class, TreeSet.class, Object.class, Boolean.class,
			Character.class, Enum.class, ChronoUnit.class, TemporalUnit.class, Period.class,
			ZoneId.class, ZoneOffset.class, DayOfWeek.class, Month.class, Year.class, YearMonth.class,
			UUID.class, Instruction.class, BasicInstruction.class, InstructionStatus.class,
			BasicInstructionStatus.class, net.solarnetwork.domain.Instruction.class,
			net.solarnetwork.domain.BasicInstruction.class,
			net.solarnetwork.domain.InstructionStatus.class,
			net.solarnetwork.domain.BasicInstructionStatus.class,
			net.solarnetwork.domain.InstructionStatus.InstructionState.class);

	/**
	 * The API request and response types.
	 */
	public static final List<Class<?>> API_TYPES = List.of(SimulationRequest.class,
			SimulationResult.class, SettingsFormRequest.class, SettingsForm.class, SettingsImport.class,
			SettingsExportRequest.class, SettingsExport.class, SettingsMergeRequest.class,
			ExternalSettingsRequest.class, ExternalSettings.class, InfoController.AppInfo.class,
			CustomFiltersReload.class, CaptureInfo.class, RunInfo.class, RunProgress.class,
			RunResult.class, DownloadDatum.class);

	/**
	 * The JDK collection implementation types, which are not public, that
	 * expressions can call methods on. SpEL finds a method on an object's own
	 * class, so a map's key set (for example from the metadata
	 * {@code infoKeys}) or an immutable list needs its class registered.
	 */
	// @formatter:off
	public static final List<String> EXPRESSION_JDK_TYPE_NAMES = List.of(
			"java.util.Arrays$ArrayList",
			"java.util.Collections$EmptyList",
			"java.util.Collections$EmptyMap",
			"java.util.Collections$EmptySet",
			"java.util.Collections$SingletonList",
			"java.util.Collections$SingletonMap",
			"java.util.Collections$SingletonSet",
			"java.util.Collections$UnmodifiableCollection",
			"java.util.Collections$UnmodifiableList",
			"java.util.Collections$UnmodifiableMap",
			"java.util.Collections$UnmodifiableRandomAccessList",
			"java.util.Collections$UnmodifiableSet",
			"java.util.HashMap$EntrySet",
			"java.util.HashMap$KeySet",
			"java.util.HashMap$Values",
			"java.util.ImmutableCollections$List12",
			"java.util.ImmutableCollections$ListN",
			"java.util.ImmutableCollections$Map1",
			"java.util.ImmutableCollections$MapN",
			"java.util.ImmutableCollections$Set12",
			"java.util.ImmutableCollections$SetN",
			"java.util.ImmutableCollections$SubList",
			"java.util.LinkedHashMap$LinkedEntrySet",
			"java.util.LinkedHashMap$LinkedKeySet",
			"java.util.LinkedHashMap$LinkedValues",
			"java.util.TreeMap$EntrySet",
			"java.util.TreeMap$KeySet",
			"java.util.TreeMap$Values",
			"java.time.ZoneRegion");
	// @formatter:on

	/**
	 * The datum types of the datum sources, from their {@code getDatumType()}.
	 */
	private static final List<Class<?>> INVOKER_DATUM_TYPES = List.of(NodeDatum.class,
			EnergyStorageDatum.class, AcEnergyDatum.class);

	@Override
	public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
		hints.resources().registerPattern("net/solarnetwork/**/*.properties");

		final Set<Class<?>> registered = new HashSet<>(64);
		for ( FilterTypeDefinition def : StandardFilterTypes.definitions() ) {
			registerBeanType(hints, def.filterClass(), registered);
		}
		// Control Conductors are configured with settings too
		registerBeanType(hints, ControlConductor.class, registered);
		// datum sources are configured through their jobs, whose properties are
		// interfaces, so each class is registered
		registerBeanType(hints, SimpleManagedJob.class, registered);
		registerBeanType(hints, DatumDataSourcePollManagedJob.class, registered);
		for ( DatumSourceTypeDefinition def : StandardDatumSourceTypes.definitions() ) {
			registerBeanType(hints, def.sourceClass(), registered);
		}
		registerBeanType(hints, DatumDataSourceOpModeInvoker.class, registered);
		// an invoker's Datum Type loads the class it names, to match the datum
		// sources' datum types and the types they extend
		final Set<Class<?>> datumTypes = new HashSet<>(16);
		for ( Class<?> type : INVOKER_DATUM_TYPES ) {
			registerTypeHierarchy(hints, type, datumTypes);
		}

		// the poll job publishes device info as metadata, serialized by Jackson
		hints.reflection().registerType(BasicDeviceInfo.class, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS,
				MemberCategory.INVOKE_PUBLIC_METHODS);
		hints.reflection().registerType(DeviceInfo.class, MemberCategory.INVOKE_PUBLIC_METHODS);

		for ( Class<?> type : EXPRESSION_TYPES ) {
			hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS);
		}
		for ( String typeName : EXPRESSION_JDK_TYPE_NAMES ) {
			hints.reflection().registerType(TypeReference.of(typeName),
					MemberCategory.INVOKE_PUBLIC_METHODS);
		}

		final Set<Type> walked = new HashSet<>(64);
		for ( Class<?> type : API_TYPES ) {
			registerElementArrayTypes(hints, type, false, walked);
		}
		// GET /api/filter-types, /api/filter-types/unavailable, and
		// /api/datum-source-types return lists, and a 400 problem detail can
		// have a list of messages
		registerElementArrayTypes(hints, FilterTypeInfo.class, true, walked);
		registerElementArrayTypes(hints, DatumSourceTypeInfo.class, true, walked);
		registerElementArrayTypes(hints, UnavailableFilterTypeInfo.class, true, walked);
		registerElementArrayTypes(hints, SimulationMessage.class, true, walked);
		// GET /api/runs/{id}/datum returns a list of traces
		registerElementArrayTypes(hints, RawValue.class, true, walked);
	}

	/**
	 * Register the array types of the types the API records hold in
	 * collections.
	 *
	 * <p>
	 * Jackson creates an element array type when it looks at the constructors
	 * of a collection class, such as the {@code ImmutableCollections$ListN}
	 * that {@code Stream.toList()} returns, and the native image can only
	 * create array types that are registered. Registering the collection
	 * classes for expressions (see {@link #EXPRESSION_JDK_TYPE_NAMES}) makes
	 * their constructors visible to Jackson.
	 * </p>
	 *
	 * @param hints
	 *        the hints
	 * @param type
	 *        the type to walk
	 * @param element
	 *        {@code true} if the type is a collection element
	 * @param walked
	 *        the types already walked
	 */
	private static void registerElementArrayTypes(RuntimeHints hints, Type type, boolean element,
			Set<Type> walked) {
		if ( type instanceof ParameterizedType pt ) {
			registerElementArrayTypes(hints, pt.getRawType(), element, walked);
			for ( Type arg : pt.getActualTypeArguments() ) {
				registerElementArrayTypes(hints, arg, true, walked);
			}
		} else if ( type instanceof Class<?> c && !c.isPrimitive() ) {
			if ( element ) {
				hints.reflection().registerType(c.arrayType());
			}
			if ( c.isRecord() && walked.add(c) ) {
				for ( RecordComponent rc : c.getRecordComponents() ) {
					registerElementArrayTypes(hints, rc.getGenericType(), false, walked);
				}
			}
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
	private static void registerTypeHierarchy(RuntimeHints hints, @Nullable Class<?> type,
			Set<Class<?>> registered) {
		if ( type == null || type == Object.class || !registered.add(type) ) {
			return;
		}
		hints.reflection().registerType(type);
		registerTypeHierarchy(hints, type.getSuperclass(), registered);
		for ( Class<?> iface : type.getInterfaces() ) {
			registerTypeHierarchy(hints, iface, registered);
		}
	}

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
