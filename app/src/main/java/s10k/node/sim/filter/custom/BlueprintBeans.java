/* ==================================================================
 * BlueprintBeans.java - 30 Oct 2026 3:40:12 pm
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;
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
import s10k.node.sim.filter.blueprint.FilterValue;
import s10k.node.sim.filter.blueprint.MessageSourceDescriptor;
import s10k.node.sim.filter.blueprint.ServiceKey;
import s10k.node.sim.filter.registry.ContextClassLoader;
import s10k.node.sim.filter.registry.FilterContext;
import s10k.node.sim.filter.registry.MessageSources;
import s10k.node.sim.filter.sim.SimulationContext;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.service.ExpressionService;

/**
 * Create the components of blueprint descriptions (filters and datum sources)
 * the way Gemini Blueprint creates them: with the blueprint's constructor
 * arguments, then its properties.
 *
 * <p>
 * SolarNode components that keep time usually have a constructor that takes a
 * {@link Clock} or {@link InstantSource} before the blueprint's arguments,
 * which tests use. The simulator uses that constructor when there is one, with
 * the simulated clock, so the component follows the datum timestamps instead of
 * the system clock. A datum source can also have a {@link RandomGenerator}
 * after the clock, like the Mock Energy Meter, and then gets the run's seeded
 * random generator, so random runs repeat.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
final class BlueprintBeans {

	/** How to get a service from the simulated services, by service key. */
	@FunctionalInterface
	interface Services {

		/**
		 * Get how to get a service.
		 *
		 * @param key
		 *        the service
		 * @return a function that gets the service, or {@code null} if the
		 *         simulator does not provide it
		 */
		@Nullable
		Function<FilterContext, @Nullable Object> service(ServiceKey key);

	}

	/** A constructor argument or property value. */
	sealed interface Binding {

	}

	/** A literal value, converted to the type it is given to. */
	record Literal(String value) implements Binding {

	}

	/** A value from the simulated services. */
	record Provided(Function<FilterContext, @Nullable Object> provider) implements Binding {

	}

	/** Thrown when a filter or datum source type is not available. */
	static final class UnavailableException extends Exception {

		private static final long serialVersionUID = 1L;

		UnavailableException(String message) {
			super(message);
		}

	}

	/** The constructor parameters before the blueprint's arguments. */
	enum Leading {

		/** A clock, then a random generator. */
		ClockAndRandom,

		/** A clock. */
		ClockOnly,

		/** None. */
		None;

	}

	/**
	 * Creates components with a chosen constructor.
	 *
	 * @param <T>
	 *        the component type
	 */
	record Creator<T>(Constructor<? extends T> constructor, Leading leading, List<Binding> arguments,
			Map<String, Binding> properties) {

		/**
		 * Create a component.
		 *
		 * @param ctx
		 *        the simulated services
		 * @return the component, with its blueprint properties set
		 */
		T create(FilterContext ctx) {
			return create(ctx, null);
		}

		/**
		 * Create a component.
		 *
		 * @param ctx
		 *        the simulated services
		 * @param rng
		 *        the random generator, for a constructor that takes one
		 * @return the component, with its blueprint properties set
		 */
		T create(FilterContext ctx, @Nullable RandomGenerator rng) {
			final @Nullable Object[] values = values(constructor, leading, arguments, ctx, rng);
			if ( values == null ) {
				throw new IllegalStateException(
						"The arguments do not match the constructor %s.".formatted(constructor));
			}
			final T result;
			try {
				result = constructor.newInstance(values);
			} catch ( InvocationTargetException e ) {
				throw unwrap(e);
			} catch ( ReflectiveOperationException e ) {
				throw new IllegalStateException(e);
			}
			final BeanWrapper bean = PropertyAccessorFactory.forBeanPropertyAccess(result);
			for ( Map.Entry<String, Binding> e : properties.entrySet() ) {
				bean.setPropertyValue(e.getKey(), value(e.getValue(), ctx));
			}
			return result;
		}

	}

	private BlueprintBeans() {
		// not available
	}

	/**
	 * Get the binding of a blueprint value.
	 *
	 * @param value
	 *        the value
	 * @param target
	 *        what the value is given to, for messages, such as
	 *        {@code property foo}
	 * @param classLoader
	 *        the class loader of the component classes
	 * @param services
	 *        the services the simulator provides
	 * @return the binding
	 * @throws UnavailableException
	 *         if the simulator cannot provide the value
	 */
	static Binding binding(FilterValue value, String target, ClassLoader classLoader, Services services)
			throws UnavailableException {
		return switch (value) {
			case FilterValue.Literal(String v) -> new Literal(v);
			case FilterValue.Service(var key) -> {
				final Function<FilterContext, @Nullable Object> provider = services.service(key);
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

	/**
	 * Get the bindings of a component's constructor arguments.
	 *
	 * @param values
	 *        the arguments
	 * @param classLoader
	 *        the class loader of the component classes
	 * @param services
	 *        the services the simulator provides
	 * @return the bindings
	 * @throws UnavailableException
	 *         if the simulator cannot provide a value
	 */
	static List<Binding> arguments(List<FilterValue> values, ClassLoader classLoader, Services services)
			throws UnavailableException {
		final List<Binding> result = new ArrayList<>(values.size());
		for ( int i = 0, len = values.size(); i < len; i++ ) {
			result.add(binding(values.get(i), "constructor argument " + (i + 1), classLoader, services));
		}
		return result;
	}

	/**
	 * Create a message source.
	 *
	 * @param descriptor
	 *        the message source description
	 * @param classLoader
	 *        the class loader of the message bundles
	 * @return the message source
	 */
	static MessageSource messageSource(MessageSourceDescriptor descriptor, ClassLoader classLoader) {
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

	/**
	 * Get a lifecycle method of a component, which runs with the component's
	 * class loader as the thread context class loader.
	 *
	 * @param <T>
	 *        the component type
	 * @param componentClass
	 *        the component class
	 * @param methodName
	 *        the method name, or {@code null}
	 * @param kind
	 *        the kind of method, for messages: {@code init} or {@code destroy}
	 * @param classNoun
	 *        the kind of class, for messages, such as {@code filter class}
	 * @param classLoader
	 *        the class loader of the component classes
	 * @return the method, or {@code null} if {@code methodName} is {@code null}
	 * @throws UnavailableException
	 *         if the class has no public method with no arguments of that name
	 */
	static <T> @Nullable Consumer<T> lifecycle(Class<?> componentClass, @Nullable String methodName,
			String kind, String classNoun, ClassLoader classLoader) throws UnavailableException {
		if ( methodName == null ) {
			return null;
		}
		final Method method;
		try {
			method = componentClass.getMethod(methodName);
		} catch ( NoSuchMethodException e ) {
			throw new UnavailableException(
					"Its %s has no public %s method %s().".formatted(classNoun, kind, methodName));
		}
		return component -> ContextClassLoader.run(classLoader, () -> invoke(method, component));
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

	/**
	 * Choose the constructor, and check that a component can be created with
	 * it, with simulated services.
	 *
	 * @param <T>
	 *        the component type
	 * @param componentClass
	 *        the component class
	 * @param arguments
	 *        the constructor argument bindings
	 * @param properties
	 *        the property bindings
	 * @param random
	 *        {@code true} to prefer a constructor that takes a random generator
	 *        after the clock
	 * @param classLoader
	 *        the class loader of the component classes
	 * @param expressionService
	 *        the expression service, for the simulated services
	 * @param classNoun
	 *        the kind of class, for messages, such as {@code filter class}
	 * @param noun
	 *        the kind of component, for messages, such as {@code filter}
	 * @return the creator
	 * @throws UnavailableException
	 *         if no constructor suits the arguments, a property cannot be set,
	 *         or the component cannot be created
	 */
	static <T> Creator<T> creator(Class<? extends T> componentClass, List<Binding> arguments,
			Map<String, Binding> properties, boolean random, ClassLoader classLoader,
			ExpressionService expressionService, String classNoun, String noun)
			throws UnavailableException {
		final SimulationContext ctx = new SimulationContext(Instant.now(), expressionService,
				(_, _) -> false, new GeneralDatumMetadata());
		final List<Constructor<?>> constructors;
		try {
			constructors = Arrays.stream(componentClass.getConstructors())
					.sorted(Comparator.comparingInt(Constructor<?>::getParameterCount)
							.thenComparing(Constructor::toGenericString))
					.toList();
		} catch ( LinkageError e ) {
			throw new UnavailableException("Its %s %s could not be loaded: %s.".formatted(classNoun,
					componentClass.getName(), e));
		}

		final RandomGenerator rng = new SplittableRandom(0L);
		Creator<T> creator = null;
		for ( Leading leading : Leading.values() ) {
			if ( leading == Leading.ClockAndRandom && !random ) {
				continue;
			}
			for ( Constructor<?> c : constructors ) {
				if ( values(c, leading, arguments, ctx, rng) != null ) {
					@SuppressWarnings("unchecked")
					Constructor<? extends T> ctor = (Constructor<? extends T>) c;
					creator = new Creator<>(ctor, leading, arguments, properties);
					break;
				}
			}
			if ( creator != null ) {
				break;
			}
		}
		if ( creator == null ) {
			throw new UnavailableException(
					"Its %s %s has no public constructor for the %d blueprint arguments."
							.formatted(classNoun, componentClass.getName(), arguments.size()));
		}

		for ( String property : properties.keySet() ) {
			final PropertyDescriptor prop = BeanUtils.getPropertyDescriptor(componentClass, property);
			if ( prop == null || prop.getWriteMethod() == null ) {
				throw new UnavailableException("Its %s %s has no %s property.".formatted(classNoun,
						componentClass.getName(), property));
			}
		}

		final Creator<T> result = creator;
		try {
			ContextClassLoader.call(classLoader, () -> result.create(ctx, rng));
		} catch ( RuntimeException | LinkageError e ) {
			final Throwable root = (e instanceof BeansException be ? be.getMostSpecificCause() : e);
			throw new UnavailableException("A %s could not be created: %s.".formatted(noun, root));
		}
		return result;
	}

	/**
	 * Get the constructor argument values, or {@code null} if they do not suit
	 * the constructor.
	 */
	private static @Nullable Object @Nullable [] values(Constructor<?> constructor, Leading leading,
			List<Binding> arguments, FilterContext ctx, @Nullable RandomGenerator rng) {
		final int offset = switch (leading) {
			case ClockAndRandom -> 2;
			case ClockOnly -> 1;
			case None -> 0;
		};
		final Class<?>[] types = constructor.getParameterTypes();
		if ( types.length != arguments.size() + offset ) {
			return null;
		}
		final @Nullable Object[] result = new Object[types.length];
		if ( offset > 0 ) {
			if ( types[0] != Clock.class && types[0] != InstantSource.class ) {
				return null;
			}
			result[0] = ctx.clock();
		}
		if ( offset > 1 ) {
			if ( types[1] != RandomGenerator.class ) {
				return null;
			}
			result[1] = rng;
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
	 * Get the problems in a description that do not stop the type from working:
	 * those that are not about the values its components are created with.
	 *
	 * @param problems
	 *        the description's problems
	 * @param values
	 *        the description's constructor arguments and property values
	 * @param valueProblem
	 *        formats the problem of an unresolved value's reason, as the
	 *        description has it
	 * @return the warnings
	 */
	static List<String> warnings(List<String> problems, List<FilterValue> values,
			Function<String, String> valueProblem) {
		final Set<String> valueProblems = new HashSet<>(4);
		for ( FilterValue v : values ) {
			if ( v instanceof FilterValue.Unresolved(String reason) ) {
				valueProblems.add(valueProblem.apply(reason));
			}
		}
		return problems.stream().filter(p -> !valueProblems.contains(p)).toList();
	}

}
