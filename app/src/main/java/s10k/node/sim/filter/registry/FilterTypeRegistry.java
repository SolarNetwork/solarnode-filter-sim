/* ==================================================================
 * FilterTypeRegistry.java - 18 Oct 2026 8:56:20 am
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

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.FilterTypeInfo;

/**
 * The registry of supported filter types.
 *
 * @author matt
 * @version 1.0
 */
public class FilterTypeRegistry {

	private final Map<String, FilterTypeDefinition> definitions;

	/**
	 * Constructor.
	 *
	 * @param definitions
	 *        the filter type definitions
	 * @throws IllegalArgumentException
	 *         if more than one definition has the same factory UID
	 */
	public FilterTypeRegistry(Collection<FilterTypeDefinition> definitions) {
		super();
		Map<String, FilterTypeDefinition> map = new LinkedHashMap<>(definitions.size());
		for ( FilterTypeDefinition def : definitions ) {
			if ( map.putIfAbsent(def.factoryUid(), def) != null ) {
				throw new IllegalArgumentException(
						"Duplicate filter type definition: " + def.factoryUid());
			}
		}
		this.definitions = Map.copyOf(map);
	}

	/**
	 * Get a filter type definition.
	 *
	 * @param factoryUid
	 *        the factory UID of the definition to get
	 * @return the definition, or {@code null} if the filter type is not
	 *         supported
	 */
	public @Nullable FilterTypeDefinition definition(String factoryUid) {
		return definitions.get(factoryUid);
	}

	/**
	 * Get all filter type definitions.
	 *
	 * @return the definitions
	 */
	public Collection<FilterTypeDefinition> definitions() {
		return definitions.values();
	}

	/**
	 * Get information about all supported filter types, sorted by name.
	 *
	 * @param locale
	 *        the locale to use
	 * @return the information
	 */
	public List<FilterTypeInfo> filterTypes(Locale locale) {
		return definitions.values().stream().map(d -> d.info(locale))
				.sorted(Comparator.comparing(FilterTypeInfo::name, String.CASE_INSENSITIVE_ORDER))
				.toList();
	}

}
