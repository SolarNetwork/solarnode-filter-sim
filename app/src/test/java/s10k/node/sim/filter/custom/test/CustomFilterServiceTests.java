/* ==================================================================
 * CustomFilterServiceTests.java - 22 Oct 2026 9:05:12 am
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

package s10k.node.sim.filter.custom.test;

import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import s10k.node.sim.filter.custom.CustomFilterLoader;
import s10k.node.sim.filter.custom.CustomFilterService;
import s10k.node.sim.filter.custom.CustomFilters;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import net.solarnetwork.common.expr.spel.SpelExpressionService;

/**
 * Test cases for the {@link CustomFilterService} class: reloading the custom
 * filters, with the test filter bundle that the build makes from the
 * {@code testFilterBundle} source set.
 *
 * @author matt
 * @version 1.0
 */
public class CustomFilterServiceTests {

	private static final String SCALE = "com.example.solarnode.filter.scale";
	private static final String IDENTITY = "com.example.solarnode.filter.identity";

	/** A resource that only the bundle has. */
	private static final String BUNDLE_RESOURCE = "com/example/solarnode/filter/ScaleDatumFilterService.properties";

	@TempDir
	private Path dir;

	private CustomFilterLoader loader;

	@BeforeEach
	public void setup() {
		loader = new CustomFilterLoader(new SpelExpressionService(), StandardFilterTypes.definitions()
				.stream().map(FilterTypeDefinition::factoryUid).toList());
	}

	private static Path bundleJar() {
		return Path.of(System.getProperty("sim.test.filterBundle"));
	}

	@Test
	public void reload() throws Exception {
		// GIVEN
		Path jar = Files.copy(bundleJar(), dir.resolve("example.jar"));
		try (CustomFilterService service = new CustomFilterService(loader, List.of(dir.toString()))) {
			FilterTypeRegistry registry = service.registry();
			ClassLoader before = registry.definition(SCALE).classLoader();
			then(before.getResource(BUNDLE_RESOURCE)).as("The class loader finds the bundle's resources")
					.isNotNull();

			// WHEN
			Files.delete(jar);
			CustomFilters removed = service.reload();

			// THEN
			// @formatter:off
			then(removed.bundles())
				.as("No bundles once the jar is removed")
				.isEmpty()
				;
			then(registry.definition(SCALE))
				.as("The registry no longer has the custom filter type")
				.isNull()
				;
			then(registry.unavailable(IDENTITY))
				.as("Nor the unavailable custom filter type")
				.isNull()
				;
			then(registry.definition(StandardFilterTypes.EXPRESSION))
				.as("The registry still has the standard filter types")
				.isNotNull()
				;
			then(before.getResource(BUNDLE_RESOURCE))
				.as("The previous class loader is closed")
				.isNull()
				;
			// @formatter:on

			// WHEN
			Files.copy(bundleJar(), jar);
			service.reload();

			// THEN
			// @formatter:off
			then(registry.definition(SCALE))
				.as("The registry has the custom filter type again, from a new class loader")
				.isNotNull()
				.extracting(FilterTypeDefinition::classLoader)
				.isNotSameAs(before)
				;
			then(service.current().definitions())
				.as("The service has the new custom filters")
				.extracting(FilterTypeDefinition::factoryUid)
				.containsExactly(SCALE)
				;
			// @formatter:on
		}
	}

	@Test
	public void reload_notConfigured() {
		// GIVEN
		try (CustomFilterService service = new CustomFilterService(loader, List.of(" "))) {
			// THEN
			// @formatter:off
			then(service.isConfigured())
				.as("A blank path is not a custom filters path")
				.isFalse()
				;
			thenThrownBy(service::reload)
				.as("Nothing to reload")
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("sim.filters.path")
				;
			// @formatter:on
		}
	}

}
