/* ==================================================================
 * CustomFilterService.java - 22 Oct 2026 9:05:12 am
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.registry.UnavailableFilterType;

/**
 * The custom filters, and the registry of all the filter types, which reloading
 * the custom filters updates.
 *
 * <p>
 * A reload loads the custom filters path again, with a new class loader, so a
 * filter author can rebuild a bundle and try it without restarting the
 * simulator. Each simulation creates its filters, so the next one uses the new
 * classes. The class loader of the previous custom filters is closed.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class CustomFilterService implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(CustomFilterService.class);

	private final CustomFilterLoader loader;
	private final List<String> path;
	private final FilterTypeRegistry registry;
	private volatile CustomFilters current;

	/**
	 * Constructor, which loads the custom filters.
	 *
	 * @param loader
	 *        the loader
	 * @param path
	 *        the custom filters path: jars, bundle project directories, and
	 *        directories of them
	 * @throws IllegalStateException
	 *         if the path is not empty and the simulator is the native
	 *         executable
	 */
	public CustomFilterService(CustomFilterLoader loader, List<String> path) {
		super();
		this.loader = loader;
		this.path = path.stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
		this.current = loader.load(this.path);
		this.registry = new FilterTypeRegistry(definitions(current), unavailable(current));
	}

	private static List<FilterTypeDefinition> definitions(CustomFilters custom) {
		final List<FilterTypeDefinition> result = new ArrayList<>(StandardFilterTypes.definitions());
		result.addAll(custom.definitions());
		return result;
	}

	private static List<UnavailableFilterType> unavailable(CustomFilters custom) {
		final List<UnavailableFilterType> result = new ArrayList<>(StandardFilterTypes.unsupported());
		result.addAll(custom.unavailable());
		return result;
	}

	/**
	 * Test if the simulator was started with a custom filters path.
	 *
	 * @return {@code true} if there is a path to load custom filters from
	 */
	public boolean isConfigured() {
		return !path.isEmpty();
	}

	/**
	 * Get the registry of all the filter types: the standard ones, plus the
	 * custom ones.
	 *
	 * @return the registry
	 */
	public FilterTypeRegistry registry() {
		return registry;
	}

	/**
	 * Get the custom filters.
	 *
	 * @return the custom filters
	 */
	public CustomFilters current() {
		return current;
	}

	/**
	 * Load the custom filters again, and update the registry.
	 *
	 * @return the new custom filters
	 * @throws IllegalStateException
	 *         if there is no custom filters path
	 */
	public synchronized CustomFilters reload() {
		if ( !isConfigured() ) {
			throw new IllegalStateException(
					"The simulator was not started with a custom filters path (sim.filters.path).");
		}
		final CustomFilters previous = current;
		final CustomFilters next = loader.load(path);
		registry.replace(definitions(next), unavailable(next));
		current = next;
		close(previous);
		return next;
	}

	private static void close(CustomFilters custom) {
		try {
			custom.close();
		} catch ( IOException e ) {
			log.warn("Error closing the custom filters class loader: {}", e.toString());
		}
	}

	@Override
	public void close() {
		close(current);
	}

}
