/* ==================================================================
 * CustomFilters.java - 21 Oct 2026 4:12:08 pm
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

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.FilterBundleInfo;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.UnavailableFilterType;

/**
 * The custom filter types loaded from the custom filters path.
 *
 * @param definitions
 *        the available filter types
 * @param unavailable
 *        the filter types that are not available
 * @param bundles
 *        the jars loaded, in path order
 * @param problems
 *        problems loading the jars or reading their blueprints, which do not
 *        belong to a filter type
 * @param resources
 *        what {@link #close()} closes: the class loader of the filter classes,
 *        and the copies of the jars it loads, or {@code null}
 * @author matt
 * @version 1.0
 */
public record CustomFilters(List<FilterTypeDefinition> definitions,
		List<UnavailableFilterType> unavailable, List<FilterBundle> bundles, List<String> problems,
		@Nullable Closeable resources) implements Closeable {

	/** No custom filters. */
	public static final CustomFilters NONE = new CustomFilters(List.of(), List.of(), List.of(),
			List.of(), null);

	/**
	 * Close the class loader of the filter classes, and delete the copies of
	 * the jars it loads, once the filter types are no longer used.
	 *
	 * @throws IOException
	 *         if the class loader cannot be closed
	 */
	@Override
	public void close() throws IOException {
		if ( resources != null ) {
			resources.close();
		}
	}

	/**
	 * Test if no custom filters path was given.
	 *
	 * @return {@code true} if no jars were loaded and there are no problems
	 */
	public boolean isEmpty() {
		return bundles.isEmpty() && problems.isEmpty();
	}

	/**
	 * Describe the custom filters, for the startup message.
	 *
	 * @param locale
	 *        the locale of the filter type names
	 * @return the lines of the description, or an empty list if
	 *         {@link #isEmpty()}
	 */
	public List<String> describe(Locale locale) {
		if ( isEmpty() ) {
			return List.of();
		}
		final Map<String, FilterTypeDefinition> defs = definitions.stream()
				.collect(Collectors.toMap(FilterTypeDefinition::factoryUid, Function.identity()));
		final List<String> lines = new ArrayList<>(8);
		lines.add("Custom filters: %d available from %d %s".formatted(definitions.size(), bundles.size(),
				bundles.size() == 1 ? "bundle" : "bundles"));
		for ( FilterBundle bundle : bundles ) {
			final String types = bundle.factoryUids().stream().map(defs::get)
					.map(d -> "%s (%s)".formatted(d.info(locale).name(), d.factoryUid()))
					.collect(Collectors.joining(", "));
			lines.add("  %s, %s: %s".formatted(bundle.label(), bundle.file(), !types.isEmpty() ? types
					: bundle.blueprint() ? "no available filter types" : "library"));
			for ( String warning : bundle.warnings() ) {
				lines.add("    Warning: " + warning);
			}
		}
		for ( UnavailableFilterType u : unavailable ) {
			final FilterBundleInfo bundle = u.bundle();
			lines.add("  Not available: %s (%s)%s. %s".formatted(u.name(), u.factoryUid(),
					bundle != null ? ", from " + bundle.label() : "", u.reason()));
		}
		for ( String problem : problems ) {
			lines.add("  Problem: " + problem);
		}
		return lines;
	}

}
