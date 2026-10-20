/* ==================================================================
 * FilterTypeRegistryTests.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.registry.test;

import static org.assertj.core.api.BDDAssertions.then;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import net.solarnetwork.common.expr.spel.SpelExpressionService;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.settings.SettingSpecifierProvider;
import s10k.node.sim.filter.domain.FilterTypeInfo;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationContext;

/**
 * Test cases for the {@link FilterTypeRegistry} and {@link StandardFilterTypes}
 * classes.
 *
 * @author matt
 * @version 1.0
 */
public class FilterTypeRegistryTests {

	@Test
	public void standardFilterTypes() {
		// GIVEN
		FilterTypeRegistry registry = new FilterTypeRegistry(StandardFilterTypes.definitions());

		// WHEN
		List<FilterTypeInfo> types = registry.filterTypes(Locale.ENGLISH);

		// THEN
		// @formatter:off
		then(types)
			.as("All user filters, and the filter chain, are supported")
			.hasSize(19)
			.allSatisfy(t -> {
				then(t.name())
					.as("Name from message source %s", t.factoryUid())
					.isNotBlank()
					;
				then(t.description())
					.as("Description from message source %s", t.factoryUid())
					.isNotBlank()
					;
			})
			.filteredOn(FilterTypeInfo::chain)
			.as("One chain type")
			.extracting(FilterTypeInfo::factoryUid)
			.containsExactly(StandardFilterTypes.CHAIN)
			;
		// @formatter:on
	}

	@Test
	public void createFilters() {
		// GIVEN
		FilterTypeRegistry registry = new FilterTypeRegistry(StandardFilterTypes.definitions());
		SimulationContext context = new SimulationContext(Instant.now(), new SpelExpressionService(),
				(_, _) -> true, new GeneralDatumMetadata());

		for ( FilterTypeDefinition def : registry.definitions() ) {
			// WHEN
			DatumFilterService filter = def.factory().create(context);
			context.injectServices(filter, def.messageSource());

			// THEN
			// @formatter:off
			then(filter)
				.as("Filter %s created with the expected type", def.factoryUid())
				.isInstanceOf(def.filterClass())
				.isInstanceOf(SettingSpecifierProvider.class)
				;
			then(((SettingSpecifierProvider) filter).getSettingSpecifiers())
				.as("Filter %s provides settings", def.factoryUid())
				.isNotEmpty()
				;
			// @formatter:on
		}
	}

}
