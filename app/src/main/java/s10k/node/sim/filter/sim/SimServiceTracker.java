/* ==================================================================
 * SimServiceTracker.java - 18 Oct 2026 7:15:05 pm
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

package s10k.node.sim.filter.sim;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.util.LinkedCaseInsensitiveMap;
import net.solarnetwork.service.Identifiable;
import net.solarnetwork.service.OptionalService.OptionalFilterableService;
import net.solarnetwork.service.OptionalServiceCollection;

/**
 * A simulated SolarNode {@code DynamicServiceTracker}, which finds services in
 * the simulation's service registry.
 *
 * <p>
 * Like the node's tracker, it finds the registered services of its type whose
 * properties match all its property filters, in registration order. A property
 * matches a filter value if it is equal to it, or if it is a collection that
 * contains it. Property filter names are case-insensitive. Only the
 * {@link Identifiable} properties of a service can be matched ({@code uid},
 * {@code groupUid}, and {@code displayName}), which are the ones SolarNode
 * filters use.
 * </p>
 *
 * @param <T>
 *        the service type
 * @author matt
 * @version 1.0
 */
public final class SimServiceTracker<T>
		implements OptionalFilterableService<T>, OptionalServiceCollection<T> {

	/** The value of a property that cannot be read. */
	private static final Object UNREADABLE = new Object();

	private final Class<T> serviceClass;
	private final Iterable<?> registry;
	private final boolean ignoreEmptyPropertyFilterValues;
	private final Map<String, Object> propertyFilters = new LinkedCaseInsensitiveMap<>(2);

	/**
	 * Constructor.
	 *
	 * @param serviceClass
	 *        the service type
	 * @param registry
	 *        the registered services, of any type
	 * @param ignoreEmptyPropertyFilterValues
	 *        {@code true} if property filters with empty values match every
	 *        service
	 */
	public SimServiceTracker(Class<T> serviceClass, Iterable<?> registry,
			boolean ignoreEmptyPropertyFilterValues) {
		super();
		this.serviceClass = serviceClass;
		this.registry = registry;
		this.ignoreEmptyPropertyFilterValues = ignoreEmptyPropertyFilterValues;
	}

	@Override
	public @Nullable T service() {
		for ( Object service : registry ) {
			if ( serviceClass.isInstance(service) && matches(service) ) {
				return serviceClass.cast(service);
			}
		}
		return null;
	}

	@Override
	public Iterable<T> services() {
		final List<T> result = new ArrayList<>(4);
		for ( Object service : registry ) {
			if ( serviceClass.isInstance(service) && matches(service) ) {
				result.add(serviceClass.cast(service));
			}
		}
		return result;
	}

	@Override
	public Map<String, ?> getPropertyFilters() {
		return propertyFilters;
	}

	@Override
	public void setPropertyFilter(String key, @Nullable Object value) {
		if ( value != null ) {
			propertyFilters.put(key, value);
		} else {
			propertyFilters.remove(key);
		}
	}

	@Override
	public @Nullable Object removePropertyFilter(String key) {
		return propertyFilters.remove(key);
	}

	// the same tests as DynamicServiceTracker.serviceMatchesFilters()
	@SuppressWarnings("ReferenceEquality")
	private boolean matches(Object service) {
		for ( Map.Entry<String, Object> e : propertyFilters.entrySet() ) {
			final Object value = property(service, e.getKey());
			if ( value == UNREADABLE ) {
				return false;
			}
			final Object required = e.getValue();
			if ( ignoreEmptyPropertyFilterValues && required instanceof String s && s.isEmpty() ) {
				continue;
			}
			if ( value instanceof Collection<?> c ? !c.contains(required) : !required.equals(value) ) {
				return false;
			}
		}
		return true;
	}

	private static @Nullable Object property(Object service, String name) {
		if ( service instanceof Identifiable s ) {
			return switch (name) {
				case Identifiable.UID_PROPERTY -> s.getUid();
				case Identifiable.GROUP_UID_PROPERTY -> s.getGroupUid();
				case "displayName" -> s.getDisplayName();
				default -> UNREADABLE;
			};
		}
		return UNREADABLE;
	}

}
