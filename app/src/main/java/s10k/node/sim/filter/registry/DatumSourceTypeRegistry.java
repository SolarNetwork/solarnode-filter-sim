/* ==================================================================
 * DatumSourceTypeRegistry.java - 29 Oct 2026 6:54:00 pm
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The registry of supported datum source types.
 *
 * @author matt
 * @version 1.0
 */
public class DatumSourceTypeRegistry {

	private final Map<String, DatumSourceTypeDefinition> definitions;

	/**
	 * Constructor.
	 *
	 * @param definitions
	 *        the datum source type definitions
	 * @throws IllegalArgumentException
	 *         if more than one definition has the same factory UID
	 */
	public DatumSourceTypeRegistry(Collection<DatumSourceTypeDefinition> definitions) {
		super();
		final Map<String, DatumSourceTypeDefinition> map = new LinkedHashMap<>(definitions.size());
		for ( DatumSourceTypeDefinition def : definitions ) {
			if ( map.putIfAbsent(def.factoryUid(), def) != null ) {
				throw new IllegalArgumentException(
						"Duplicate datum source type definition: " + def.factoryUid());
			}
		}
		this.definitions = map;
	}

	/**
	 * Create a registry of the standard datum source types.
	 *
	 * @return the registry
	 */
	public static DatumSourceTypeRegistry standard() {
		return new DatumSourceTypeRegistry(StandardDatumSourceTypes.definitions());
	}

	/**
	 * Get a datum source type definition.
	 *
	 * @param factoryUid
	 *        the factory UID
	 * @return the definition, or {@code null} if the type is not supported
	 */
	public @Nullable DatumSourceTypeDefinition definition(@Nullable String factoryUid) {
		return (factoryUid != null ? definitions.get(factoryUid) : null);
	}

	/**
	 * Get every datum source type definition.
	 *
	 * @return the definitions, in registration order
	 */
	public List<DatumSourceTypeDefinition> definitions() {
		return List.copyOf(definitions.values());
	}

}
