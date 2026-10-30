/* ==================================================================
 * BlueprintFilterTypes.java - 21 Oct 2026 4:12:08 pm
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

import java.time.Clock;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter;
import s10k.node.sim.filter.blueprint.FilterTypeDescriptor;
import s10k.node.sim.filter.blueprint.FilterValue;
import s10k.node.sim.filter.blueprint.MessageSourceDescriptor;
import s10k.node.sim.filter.custom.BlueprintBeans.Binding;
import s10k.node.sim.filter.custom.BlueprintBeans.Creator;
import s10k.node.sim.filter.custom.BlueprintBeans.UnavailableException;
import s10k.node.sim.filter.registry.ContextClassLoader;
import s10k.node.sim.filter.registry.FilterFactory;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeOrigin;
import s10k.node.sim.filter.registry.MessageSources;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationContext;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ExpressionService;

/**
 * Define filter types from their blueprint descriptions.
 *
 * <p>
 * Each filter is created the way Gemini Blueprint creates it (see
 * {@link BlueprintBeans}): with the blueprint's constructor arguments, then its
 * properties, apart from those that
 * {@link SimulationContext#injectServices(DatumFilterService, MessageSource)}
 * sets. A filter type is available only if every service its blueprint refers
 * to is one the simulator provides (see {@link ServiceBindings}).
 * </p>
 *
 * <p>
 * SolarNode filters that keep time usually have a constructor that takes a
 * {@link Clock} or {@link InstantSource} before the blueprint's arguments,
 * which tests use. The simulator uses that constructor when there is one, with
 * the simulated clock, so the filter follows the datum timestamps instead of
 * the system clock.
 * </p>
 *
 * <p>
 * Each filter type is checked by creating one filter, with simulated services,
 * so a filter that cannot be created (for example because a class it needs is
 * missing) is reported when the type is defined.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public final class BlueprintFilterTypes {

	/** The result of defining a filter type. */
	public sealed interface Result {

		/**
		 * Get the factory UID.
		 *
		 * @return the factory UID
		 */
		String factoryUid();

	}

	/**
	 * A filter type that is available.
	 *
	 * @param definition
	 *        the definition
	 * @param warnings
	 *        problems in the blueprint that do not stop the filter type from
	 *        working, such as a missing display name
	 */
	public record Defined(FilterTypeDefinition definition, List<String> warnings) implements Result {

		@Override
		public String factoryUid() {
			return definition.factoryUid();
		}

	}

	/**
	 * A filter type that is not available.
	 *
	 * @param factoryUid
	 *        the factory UID
	 * @param displayName
	 *        the display name, or {@code null}
	 * @param reason
	 *        why the filter type is not available, as a sentence
	 */
	public record Unavailable(String factoryUid, @Nullable String displayName, String reason)
			implements Result {

	}

	private static final String FILTER_CLASS = "filter class";

	private BlueprintFilterTypes() {
		// not available
	}

	/**
	 * Define a filter type.
	 *
	 * @param descriptor
	 *        the filter type's blueprint description
	 * @param classLoader
	 *        the class loader of the filter classes
	 * @param origin
	 *        the bundle the filter type comes from, or {@code null}
	 * @param expressionService
	 *        the expression service, for the simulated services the check
	 *        creates a filter with
	 * @return the result
	 */
	public static Result define(FilterTypeDescriptor descriptor, ClassLoader classLoader,
			@Nullable FilterTypeOrigin origin, ExpressionService expressionService) {
		try {
			return new Defined(definition(descriptor, classLoader, origin, expressionService),
					warnings(descriptor));
		} catch ( UnavailableException e ) {
			return new Unavailable(descriptor.factoryUid(), descriptor.displayName(),
					String.valueOf(e.getMessage()));
		}
	}

	private static FilterTypeDefinition definition(FilterTypeDescriptor descriptor,
			ClassLoader classLoader, @Nullable FilterTypeOrigin origin,
			ExpressionService expressionService) throws UnavailableException {
		// before the filter class reads it, like the standard filter classes
		StandardFilterTypes.initNodeHome();
		final Class<? extends DatumFilterService> filterClass = filterClass(descriptor.filterClassName(),
				classLoader);

		final MessageSourceDescriptor msDescriptor = descriptor.messageSource();
		final MessageSource messageSource = (msDescriptor != null
				? BlueprintBeans.messageSource(msDescriptor, classLoader)
				: MessageSources.messageSource(classLoader));

		final List<Binding> arguments = BlueprintBeans.arguments(descriptor.arguments(), classLoader,
				ServiceBindings::service);
		final Map<String, Binding> properties = new LinkedHashMap<>(descriptor.properties().size());
		for ( Map.Entry<String, FilterValue> e : descriptor.properties().entrySet() ) {
			if ( isInjected(e.getKey(), filterClass) ) {
				continue;
			}
			properties.put(e.getKey(), BlueprintBeans.binding(e.getValue(), "property " + e.getKey(),
					classLoader, ServiceBindings::service));
		}

		final @Nullable Consumer<DatumFilterService> init = BlueprintBeans.lifecycle(filterClass,
				descriptor.initMethod(), "init", FILTER_CLASS, classLoader);
		final @Nullable Consumer<DatumFilterService> destroy = BlueprintBeans.lifecycle(filterClass,
				descriptor.destroyMethod(), "destroy", FILTER_CLASS, classLoader);

		final Creator<DatumFilterService> creator = BlueprintBeans.creator(filterClass, arguments,
				properties, false, classLoader, expressionService, FILTER_CLASS, "filter");
		final FilterFactory factory = ctx -> ContextClassLoader.call(classLoader,
				() -> creator.create(ctx));

		final String displayName = (descriptor.displayName() != null ? descriptor.displayName()
				: filterClass.getSimpleName());
		return new FilterTypeDefinition(descriptor.factoryUid(), displayName, filterClass, messageSource,
				factory, init, destroy, descriptor.capabilities(), null, null, origin);
	}

	private static Class<? extends DatumFilterService> filterClass(String className,
			ClassLoader classLoader) throws UnavailableException {
		final Class<?> c;
		try {
			c = Class.forName(className, false, classLoader);
		} catch ( ClassNotFoundException | LinkageError e ) {
			throw new UnavailableException(
					"Its filter class %s could not be loaded: %s.".formatted(className, e));
		}
		if ( !DatumFilterService.class.isAssignableFrom(c) ) {
			throw new UnavailableException("Its filter class %s is not a %s.".formatted(className,
					DatumFilterService.class.getName()));
		}
		return c.asSubclass(DatumFilterService.class);
	}

	/**
	 * Test if a property is one the simulated services replace.
	 */
	private static boolean isInjected(String property, Class<?> filterClass) {
		final Class<?> base = SimulationContext.INJECTED_PROPERTIES.get(property);
		return (base != null && base.isAssignableFrom(filterClass));
	}

	/**
	 * Get the problems in a description that do not stop the filter type from
	 * working: those that are not about the values the filter is created with.
	 */
	private static List<String> warnings(FilterTypeDescriptor descriptor) {
		final List<FilterValue> values = new ArrayList<>(descriptor.arguments());
		values.addAll(descriptor.properties().values());
		return BlueprintBeans.warnings(descriptor.problems(), values,
				reason -> FilterBlueprintInterpreter.problem(descriptor.factoryUid(), reason));
	}

}
