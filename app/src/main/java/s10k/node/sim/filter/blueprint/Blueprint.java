/* ==================================================================
 * Blueprint.java - 21 Oct 2026 2:05:41 pm
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

package s10k.node.sim.filter.blueprint;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The components of an OSGi blueprint, limited to what SolarNode filter bundles
 * use.
 *
 * <p>
 * All the blueprint XML files of a bundle make up one blueprint container, so
 * use {@link #merge(Collection)} to combine them before resolving references.
 * </p>
 *
 * @param beans
 *        the top-level beans with an ID, by ID
 * @param references
 *        the top-level service references (and reference lists), by ID
 * @param services
 *        the top-level services
 * @param managedServiceFactories
 *        the managed service factories
 * @param problems
 *        problems found while reading the blueprint
 * @author matt
 * @version 1.0
 */
public record Blueprint(Map<String, Bean> beans, Map<String, Reference> references,
		List<Service> services, List<ManagedServiceFactory> managedServiceFactories,
		List<String> problems) {

	/**
	 * Combine blueprints, like the blueprint container of a bundle does with
	 * all its blueprint XML files.
	 *
	 * @param blueprints
	 *        the blueprints to combine
	 * @return the combined blueprint
	 */
	public static Blueprint merge(Collection<Blueprint> blueprints) {
		final Map<String, Bean> beans = new LinkedHashMap<>();
		final Map<String, Reference> references = new LinkedHashMap<>();
		final List<Service> services = new ArrayList<>();
		final List<ManagedServiceFactory> factories = new ArrayList<>();
		final List<String> problems = new ArrayList<>();
		for ( Blueprint bp : blueprints ) {
			problems.addAll(bp.problems);
			for ( Map.Entry<String, Bean> e : bp.beans.entrySet() ) {
				if ( references.containsKey(e.getKey())
						|| beans.putIfAbsent(e.getKey(), e.getValue()) != null ) {
					problems.add("Duplicate component ID [%s].".formatted(e.getKey()));
				}
			}
			for ( Map.Entry<String, Reference> e : bp.references.entrySet() ) {
				if ( beans.containsKey(e.getKey())
						|| references.putIfAbsent(e.getKey(), e.getValue()) != null ) {
					problems.add("Duplicate component ID [%s].".formatted(e.getKey()));
				}
			}
			services.addAll(bp.services);
			factories.addAll(bp.managedServiceFactories);
		}
		return new Blueprint(Collections.unmodifiableMap(beans), Collections.unmodifiableMap(references),
				List.copyOf(services), List.copyOf(factories), List.copyOf(problems));
	}

	/**
	 * A {@code <bean>}.
	 *
	 * @param id
	 *        the ID, or {@code null} for an inline bean without one
	 * @param className
	 *        the class name, or {@code null} if not given
	 * @param factoryMethod
	 *        the factory method, or {@code null}
	 * @param factoryRef
	 *        the ID of the component to call the factory method on, or
	 *        {@code null} for a static factory method
	 * @param initMethod
	 *        the init method, or {@code null}
	 * @param destroyMethod
	 *        the destroy method, or {@code null}
	 * @param arguments
	 *        the constructor (or factory method) arguments, in order
	 * @param properties
	 *        the properties, by name, in order
	 */
	public record Bean(@Nullable String id, @Nullable String className, @Nullable String factoryMethod,
			@Nullable String factoryRef, @Nullable String initMethod, @Nullable String destroyMethod,
			List<Value> arguments, Map<String, Value> properties) {

	}

	/**
	 * A {@code <reference>} or {@code <reference-list>}.
	 *
	 * @param id
	 *        the ID, or {@code null} for an inline reference without one
	 * @param interfaceName
	 *        the service interface, or {@code null} if not given
	 * @param filter
	 *        the service filter, or {@code null}
	 * @param list
	 *        {@code true} for a {@code <reference-list>}
	 */
	public record Reference(@Nullable String id, @Nullable String interfaceName, @Nullable String filter,
			boolean list) {

	}

	/**
	 * A {@code <service>}.
	 *
	 * @param ref
	 *        the ID of the component to publish, or {@code null} if the service
	 *        has an inline bean
	 * @param interfaces
	 *        the service interfaces
	 * @param serviceProperties
	 *        the service properties
	 * @param bean
	 *        the inline bean to publish, or {@code null}
	 */
	public record Service(@Nullable String ref, List<String> interfaces,
			Map<String, Value> serviceProperties, @Nullable Bean bean) {

	}

	/**
	 * An {@code osgix:managed-service-factory}, which creates a bean (and
	 * publishes it as a service) for each configuration of its factory PID.
	 *
	 * @param factoryPid
	 *        the factory PID, or {@code null} if not given
	 * @param interfaces
	 *        the service interfaces
	 * @param serviceProperties
	 *        the service properties
	 * @param bean
	 *        the bean to create for each configuration, or {@code null} if not
	 *        given
	 */
	public record ManagedServiceFactory(@Nullable String factoryPid, List<String> interfaces,
			Map<String, Value> serviceProperties, @Nullable Bean bean) {

	}

	/** A bean argument, property, or service property value. */
	public sealed interface Value
			permits Literal, Ref, BeanValue, ReferenceValue, ListValue, MapValue, Unsupported {

	}

	/**
	 * A literal value, from a {@code value} attribute or {@code <value>}
	 * element.
	 *
	 * @param value
	 *        the value
	 */
	public record Literal(String value) implements Value {

	}

	/**
	 * A reference to another component, from a {@code ref} attribute or
	 * {@code <ref>} element.
	 *
	 * @param id
	 *        the ID of the component
	 */
	public record Ref(String id) implements Value {

	}

	/**
	 * An inline {@code <bean>}.
	 *
	 * @param bean
	 *        the bean
	 */
	public record BeanValue(Bean bean) implements Value {

	}

	/**
	 * An inline {@code <reference>} or {@code <reference-list>}.
	 *
	 * @param reference
	 *        the reference
	 */
	public record ReferenceValue(Reference reference) implements Value {

	}

	/**
	 * A {@code <list>}, {@code <array>}, or {@code <set>}.
	 *
	 * @param values
	 *        the values
	 */
	public record ListValue(List<Value> values) implements Value {

	}

	/**
	 * A {@code <map>}, of {@code <entry>} elements.
	 *
	 * @param entries
	 *        the entries, by key
	 */
	public record MapValue(Map<String, Value> entries) implements Value {

	}

	/**
	 * A value the reader does not support.
	 *
	 * @param description
	 *        a description of the value, like {@code <props>}
	 */
	public record Unsupported(String description) implements Value {

	}

}
