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

import java.beans.PropertyDescriptor;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyAccessorFactory;
import org.springframework.beans.SimpleTypeConverter;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.core.MethodParameter;
import org.springframework.util.ClassUtils;
import s10k.node.sim.filter.blueprint.FilterBlueprintInterpreter;
import s10k.node.sim.filter.blueprint.FilterTypeDescriptor;
import s10k.node.sim.filter.blueprint.FilterValue;
import s10k.node.sim.filter.blueprint.MessageSourceDescriptor;
import s10k.node.sim.filter.registry.ContextClassLoader;
import s10k.node.sim.filter.registry.FilterContext;
import s10k.node.sim.filter.registry.FilterFactory;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeOrigin;
import s10k.node.sim.filter.registry.MessageSources;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationContext;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ExpressionService;

/**
 * Define filter types from their blueprint descriptions.
 *
 * <p>
 * Each filter is created the way Gemini Blueprint creates it: with the
 * blueprint's constructor arguments, then its properties, apart from those that
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
 * @version 1.0
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

	/** A constructor argument or property value. */
	private sealed interface Binding {

	}

	/** A literal value, converted to the type it is given to. */
	private record Literal(String value) implements Binding {

	}

	/** A value from the simulated services. */
	private record Provided(Function<FilterContext, @Nullable Object> provider) implements Binding {

	}

	/** Thrown when a filter type is not available. */
	private static final class UnavailableException extends Exception {

		private static final long serialVersionUID = 1L;

		private UnavailableException(String message) {
			super(message);
		}

	}

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
				? messageSource(msDescriptor, classLoader)
				: MessageSources.messageSource(classLoader));

		final List<Binding> arguments = new ArrayList<>(descriptor.arguments().size());
		for ( int i = 0, len = descriptor.arguments().size(); i < len; i++ ) {
			arguments.add(binding(descriptor.arguments().get(i), "constructor argument " + (i + 1),
					classLoader));
		}
		final Map<String, Binding> properties = new LinkedHashMap<>(descriptor.properties().size());
		for ( Map.Entry<String, FilterValue> e : descriptor.properties().entrySet() ) {
			if ( isInjected(e.getKey(), filterClass) ) {
				continue;
			}
			properties.put(e.getKey(), binding(e.getValue(), "property " + e.getKey(), classLoader));
		}

		final @Nullable Consumer<DatumFilterService> init = lifecycle(filterClass,
				descriptor.initMethod(), "init", classLoader);
		final @Nullable Consumer<DatumFilterService> destroy = lifecycle(filterClass,
				descriptor.destroyMethod(), "destroy", classLoader);

		final Creator creator = creator(filterClass, arguments, properties, classLoader,
				expressionService);
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

	private static Binding binding(FilterValue value, String target, ClassLoader classLoader)
			throws UnavailableException {
		return switch (value) {
			case FilterValue.Literal(String v) -> new Literal(v);
			case FilterValue.Service(var key) -> {
				final Function<FilterContext, @Nullable Object> provider = ServiceBindings.service(key);
				if ( provider == null ) {
					throw new UnavailableException(
							"It needs the %s service, which the simulator does not provide."
									.formatted(ServiceBindings.describe(key)));
				}
				yield new Provided(provider);
			}
			case FilterValue.ServiceMethod(var key, String method) -> {
				final Function<FilterContext, @Nullable Object> provider = ServiceBindings
						.serviceMethod(key, method);
				if ( provider == null ) {
					throw new UnavailableException(
							"It needs the %s() result of the %s service, which the simulator does not provide."
									.formatted(method, ServiceBindings.describe(key)));
				}
				yield new Provided(provider);
			}
			case FilterValue.Messages(var ms) -> {
				final MessageSource m = messageSource(ms, classLoader);
				yield new Provided(_ -> m);
			}
			case FilterValue.Unresolved(String reason) -> throw new UnavailableException(
					"The blueprint value of its %s is not supported: %s.".formatted(target, reason));
		};
	}

	private static MessageSource messageSource(MessageSourceDescriptor descriptor,
			ClassLoader classLoader) {
		return switch (descriptor) {
			case MessageSourceDescriptor.Bundle(List<String> basenames, var parent) -> {
				final ReloadableResourceBundleMessageSource ms = MessageSources
						.messageSource(classLoader, basenames.toArray(String[]::new));
				if ( parent != null ) {
					ms.setParentMessageSource(messageSource(parent, classLoader));
				}
				yield ms;
			}
			case MessageSourceDescriptor.Prefixed(String prefix, var delegate) -> MessageSources
					.prefixed(prefix, messageSource(delegate, classLoader));
		};
	}

	private static @Nullable Consumer<DatumFilterService> lifecycle(Class<?> filterClass,
			@Nullable String methodName, String kind, ClassLoader classLoader)
			throws UnavailableException {
		if ( methodName == null ) {
			return null;
		}
		final Method method;
		try {
			method = filterClass.getMethod(methodName);
		} catch ( NoSuchMethodException e ) {
			throw new UnavailableException(
					"Its filter class has no public %s method %s().".formatted(kind, methodName));
		}
		return filter -> ContextClassLoader.run(classLoader, () -> invoke(method, filter));
	}

	private static void invoke(Method method, Object target) {
		try {
			method.invoke(target);
		} catch ( InvocationTargetException e ) {
			throw unwrap(e);
		} catch ( IllegalAccessException e ) {
			throw new IllegalStateException(e);
		}
	}

	private static RuntimeException unwrap(InvocationTargetException e) {
		final Throwable cause = e.getCause();
		if ( cause instanceof RuntimeException re ) {
			return re;
		} else if ( cause instanceof Error err ) {
			throw err;
		}
		return new IllegalStateException(cause);
	}

	/** Creates filters with a chosen constructor. */
	private record Creator(Constructor<? extends DatumFilterService> constructor, boolean clock,
			List<Binding> arguments, Map<String, Binding> properties) {

		private DatumFilterService create(FilterContext ctx) {
			final @Nullable Object[] values = values(constructor, clock, arguments, ctx);
			if ( values == null ) {
				throw new IllegalStateException(
						"The arguments do not match the constructor %s.".formatted(constructor));
			}
			final DatumFilterService filter;
			try {
				filter = constructor.newInstance(values);
			} catch ( InvocationTargetException e ) {
				throw unwrap(e);
			} catch ( ReflectiveOperationException e ) {
				throw new IllegalStateException(e);
			}
			final BeanWrapper bean = PropertyAccessorFactory.forBeanPropertyAccess(filter);
			for ( Map.Entry<String, Binding> e : properties.entrySet() ) {
				bean.setPropertyValue(e.getKey(), value(e.getValue(), ctx));
			}
			return filter;
		}

	}

	/**
	 * Choose the constructor, and check that a filter can be created with it.
	 */
	private static Creator creator(Class<? extends DatumFilterService> filterClass,
			List<Binding> arguments, Map<String, Binding> properties, ClassLoader classLoader,
			ExpressionService expressionService) throws UnavailableException {
		final SimulationContext ctx = new SimulationContext(Instant.now(), expressionService,
				(_, _) -> false, new GeneralDatumMetadata());
		final List<Constructor<?>> constructors;
		try {
			constructors = Arrays.stream(filterClass.getConstructors())
					.sorted(Comparator.comparingInt(Constructor<?>::getParameterCount)
							.thenComparing(Constructor::toGenericString))
					.toList();
		} catch ( LinkageError e ) {
			throw new UnavailableException(
					"Its filter class %s could not be loaded: %s.".formatted(filterClass.getName(), e));
		}

		Creator creator = null;
		for ( boolean clock : new boolean[] { true, false } ) {
			for ( Constructor<?> c : constructors ) {
				if ( values(c, clock, arguments, ctx) != null ) {
					@SuppressWarnings("unchecked")
					Constructor<? extends DatumFilterService> ctor = (Constructor<? extends DatumFilterService>) c;
					creator = new Creator(ctor, clock, arguments, properties);
					break;
				}
			}
			if ( creator != null ) {
				break;
			}
		}
		if ( creator == null ) {
			throw new UnavailableException(
					"Its filter class %s has no public constructor for the %d blueprint arguments."
							.formatted(filterClass.getName(), arguments.size()));
		}

		for ( String property : properties.keySet() ) {
			final PropertyDescriptor prop = BeanUtils.getPropertyDescriptor(filterClass, property);
			if ( prop == null || prop.getWriteMethod() == null ) {
				throw new UnavailableException("Its filter class %s has no %s property."
						.formatted(filterClass.getName(), property));
			}
		}

		final Creator result = creator;
		try {
			ContextClassLoader.call(classLoader, () -> result.create(ctx));
		} catch ( RuntimeException | LinkageError e ) {
			final Throwable root = (e instanceof BeansException be ? be.getMostSpecificCause() : e);
			throw new UnavailableException("A filter could not be created: %s.".formatted(root));
		}
		return result;
	}

	/**
	 * Get the constructor argument values, or {@code null} if they do not suit
	 * the constructor.
	 */
	private static @Nullable Object @Nullable [] values(Constructor<?> constructor, boolean clock,
			List<Binding> arguments, FilterContext ctx) {
		final int offset = (clock ? 1 : 0);
		final Class<?>[] types = constructor.getParameterTypes();
		if ( types.length != arguments.size() + offset ) {
			return null;
		}
		final @Nullable Object[] result = new Object[types.length];
		if ( clock ) {
			if ( types[0] != Clock.class && types[0] != InstantSource.class ) {
				return null;
			}
			result[0] = ctx.clock();
		}
		final SimpleTypeConverter converter = new SimpleTypeConverter();
		for ( int i = 0; i < arguments.size(); i++ ) {
			final int p = i + offset;
			final Binding arg = arguments.get(i);
			if ( arg instanceof Literal(String v) ) {
				try {
					result[p] = converter.convertIfNecessary(v, types[p],
							MethodParameter.forExecutable(constructor, p));
				} catch ( BeansException | IllegalArgumentException e ) {
					return null;
				}
			} else {
				final Object v = value(arg, ctx);
				if ( v == null ? types[p].isPrimitive()
						: !ClassUtils.resolvePrimitiveIfNecessary(types[p]).isInstance(v) ) {
					return null;
				}
				result[p] = v;
			}
		}
		return result;
	}

	private static @Nullable Object value(Binding binding, FilterContext ctx) {
		return switch (binding) {
			case Literal(String v) -> v;
			case Provided(var provider) -> provider.apply(ctx);
		};
	}

	/**
	 * Get the problems in a description that do not stop the filter type from
	 * working: those that are not about the values the filter is created with.
	 */
	private static List<String> warnings(FilterTypeDescriptor descriptor) {
		final Set<String> valueProblems = new HashSet<>(4);
		final List<FilterValue> values = new ArrayList<>(descriptor.arguments());
		values.addAll(descriptor.properties().values());
		for ( FilterValue v : values ) {
			if ( v instanceof FilterValue.Unresolved(String reason) ) {
				valueProblems.add(FilterBlueprintInterpreter.problem(descriptor.factoryUid(), reason));
			}
		}
		return descriptor.problems().stream().filter(p -> !valueProblems.contains(p)).toList();
	}

}
