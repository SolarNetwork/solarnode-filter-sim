/* ==================================================================
 * BlueprintDatumSourceTypes.java - 30 Oct 2026 4:05:19 pm
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

package s10k.node.sim.filter.custom;

import java.beans.PropertyDescriptor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.context.MessageSource;
import s10k.node.sim.filter.blueprint.DatumSourceTypeDescriptor;
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter;
import s10k.node.sim.filter.blueprint.FilterValue;
import s10k.node.sim.filter.blueprint.MessageSourceDescriptor;
import s10k.node.sim.filter.custom.BlueprintBeans.Binding;
import s10k.node.sim.filter.custom.BlueprintBeans.Creator;
import s10k.node.sim.filter.custom.BlueprintBeans.UnavailableException;
import s10k.node.sim.filter.registry.ContextClassLoader;
import s10k.node.sim.filter.registry.DatumSourceFactory;
import s10k.node.sim.filter.registry.DatumSourceTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeOrigin;
import s10k.node.sim.filter.registry.MessageSources;
import s10k.node.sim.filter.registry.StandardDatumSourceTypes;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.service.DatumDataSource;
import net.solarnetwork.node.service.DatumSourceIdProvider;
import net.solarnetwork.node.service.MultiDatumDataSource;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.node.service.TariffScheduleProvider;
import net.solarnetwork.service.ExpressionService;

/**
 * Define datum source types from their blueprint descriptions.
 *
 * <p>
 * Each datum source is created the way Gemini Blueprint creates it (see
 * {@link BlueprintBeans}): with the blueprint's constructor arguments, then its
 * properties, then its init method. The simulator wraps it in the node's poll
 * job and managed job, as it does a standard datum source: as the poll job's
 * {@code multiDatumDataSource} if the blueprint gives it that property (a
 * {@link MultiDatumDataSource}), else as its {@code datumDataSource}. A datum
 * source type is available only if every service its blueprint refers to is one
 * the simulator provides (see {@link ServiceBindings#datumSourceService}).
 * </p>
 *
 * <p>
 * Each datum source type is checked by creating one datum source, with
 * simulated services, so a datum source that cannot be created (for example
 * because a class it needs is missing) is reported when the type is defined.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class BlueprintDatumSourceTypes {

	/** The result of defining a datum source type. */
	public sealed interface Result {

		/**
		 * Get the factory UID.
		 *
		 * @return the factory UID
		 */
		String factoryUid();

	}

	/**
	 * A datum source type that is available.
	 *
	 * @param definition
	 *        the definition
	 * @param warnings
	 *        problems in the blueprint that do not stop the datum source type
	 *        from working, such as a missing display name
	 */
	public record Defined(DatumSourceTypeDefinition definition, List<String> warnings)
			implements Result {

		@Override
		public String factoryUid() {
			return definition.factoryUid();
		}

	}

	/**
	 * A datum source type that is not available.
	 *
	 * @param factoryUid
	 *        the factory UID
	 * @param displayName
	 *        the display name, or {@code null}
	 * @param reason
	 *        why the datum source type is not available, as a sentence
	 */
	public record Unavailable(String factoryUid, @Nullable String displayName, String reason)
			implements Result {

	}

	private static final String SOURCE_CLASS = "datum source class";

	/** The service interfaces the simulator knows, by name. */
	private static final Map<String, Class<?>> SERVICES = Map.of(DatumDataSource.class.getName(),
			DatumDataSource.class, MultiDatumDataSource.class.getName(), MultiDatumDataSource.class,
			NodeControlProvider.class.getName(), NodeControlProvider.class,
			InstructionHandler.class.getName(), InstructionHandler.class,
			TariffScheduleProvider.class.getName(), TariffScheduleProvider.class);

	private BlueprintDatumSourceTypes() {
		// not available
	}

	/**
	 * Define a datum source type.
	 *
	 * @param descriptor
	 *        the datum source type's blueprint description
	 * @param classLoader
	 *        the class loader of the datum source classes
	 * @param origin
	 *        the bundle the datum source type comes from, or {@code null}
	 * @param expressionService
	 *        the expression service, for the simulated services the check
	 *        creates a datum source with
	 * @return the result
	 */
	public static Result define(DatumSourceTypeDescriptor descriptor, ClassLoader classLoader,
			@Nullable FilterTypeOrigin origin, ExpressionService expressionService) {
		try {
			return new Defined(definition(descriptor, classLoader, origin, expressionService),
					warnings(descriptor));
		} catch ( UnavailableException e ) {
			return new Unavailable(descriptor.factoryUid(), descriptor.displayName(),
					String.valueOf(e.getMessage()));
		}
	}

	private static DatumSourceTypeDefinition definition(DatumSourceTypeDescriptor descriptor,
			ClassLoader classLoader, @Nullable FilterTypeOrigin origin,
			ExpressionService expressionService) throws UnavailableException {
		// before the datum source class reads it, like the standard classes
		StandardFilterTypes.initNodeHome();
		final Class<? extends DatumSourceIdProvider> sourceClass = sourceClass(
				descriptor.sourceClassName(), descriptor.multi(), classLoader);

		final MessageSourceDescriptor msDescriptor = descriptor.messageSource();
		final MessageSource messageSource = (msDescriptor != null
				? BlueprintBeans.messageSource(msDescriptor, classLoader)
				: MessageSources.messageSource(classLoader));

		final List<Binding> arguments = BlueprintBeans.arguments(descriptor.arguments(), classLoader,
				ServiceBindings::datumSourceService);
		final Map<String, Binding> properties = new LinkedHashMap<>(descriptor.properties().size());
		for ( Map.Entry<String, FilterValue> e : descriptor.properties().entrySet() ) {
			properties.put(e.getKey(), BlueprintBeans.binding(e.getValue(), "property " + e.getKey(),
					classLoader, ServiceBindings::datumSourceService));
		}

		final @Nullable Consumer<DatumSourceIdProvider> init = BlueprintBeans.lifecycle(sourceClass,
				descriptor.initMethod(), "init", SOURCE_CLASS, classLoader);

		final Creator<DatumSourceIdProvider> creator = BlueprintBeans.creator(sourceClass, arguments,
				properties, true, classLoader, expressionService, SOURCE_CLASS, "datum source");
		final DatumSourceFactory factory = (ctx, rng) -> ContextClassLoader.call(classLoader, () -> {
			final DatumSourceIdProvider source = creator.create(ctx, rng);
			if ( init != null ) {
				init.accept(source);
			}
			return source;
		});

		final List<Class<?>> services = new ArrayList<>(4);
		for ( String name : descriptor.serviceInterfaces() ) {
			final Class<?> service = SERVICES.get(name);
			if ( service != null ) {
				services.add(service);
			}
		}

		final String displayName = (descriptor.displayName() != null ? descriptor.displayName()
				: sourceClass.getSimpleName());
		final String schedule = (descriptor.defaultSchedule() != null
				&& !descriptor.defaultSchedule().isBlank() ? descriptor.defaultSchedule()
						: StandardDatumSourceTypes.DEFAULT_SCHEDULE);
		return new DatumSourceTypeDefinition(descriptor.factoryUid(), displayName, sourceClass,
				messageSource, factory, schedule, List.copyOf(services), sourceIdGetter(sourceClass),
				null, null, origin, descriptor.multi());
	}

	private static Class<? extends DatumSourceIdProvider> sourceClass(String className, boolean multi,
			ClassLoader classLoader) throws UnavailableException {
		final Class<?> c;
		try {
			c = Class.forName(className, false, classLoader);
		} catch ( ClassNotFoundException | LinkageError e ) {
			throw new UnavailableException(
					"Its datum source class %s could not be loaded: %s.".formatted(className, e));
		}
		final Class<? extends DatumSourceIdProvider> type = (multi ? MultiDatumDataSource.class
				: DatumDataSource.class);
		if ( !type.isAssignableFrom(c) ) {
			throw new UnavailableException(
					"Its datum source class %s is not a %s.".formatted(className, type.getName()));
		}
		return c.asSubclass(type);
	}

	/**
	 * Get the Source ID setting of a datum source class: its {@code sourceId}
	 * property, which most datum sources have.
	 *
	 * @return the getter, or {@code null} if the class has no such property
	 */
	private static @Nullable Function<DatumSourceIdProvider, @Nullable String> sourceIdGetter(
			Class<?> sourceClass) {
		final PropertyDescriptor prop = BeanUtils.getPropertyDescriptor(sourceClass, "sourceId");
		final Method getter = (prop != null ? prop.getReadMethod() : null);
		if ( getter == null || getter.getReturnType() != String.class ) {
			return null;
		}
		return source -> {
			try {
				return (String) getter.invoke(source);
			} catch ( IllegalAccessException | InvocationTargetException e ) {
				return null;
			}
		};
	}

	/**
	 * Get the problems in a description that do not stop the datum source type
	 * from working: those that are not about the values the datum source is
	 * created with.
	 */
	private static List<String> warnings(DatumSourceTypeDescriptor descriptor) {
		final List<FilterValue> values = new ArrayList<>(descriptor.arguments());
		values.addAll(descriptor.properties().values());
		return BlueprintBeans.warnings(descriptor.problems(), values,
				reason -> FilterBlueprintInterpreter.datumSourceProblem(descriptor.factoryUid(),
						reason));
	}

}
