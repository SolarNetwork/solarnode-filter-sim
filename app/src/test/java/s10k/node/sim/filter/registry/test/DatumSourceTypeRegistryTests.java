/* ==================================================================
 * DatumSourceTypeRegistryTests.java - 29 Oct 2026 9:40:00 pm
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
import static org.assertj.core.groups.Tuple.tuple;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.DatumSourceCapability;
import s10k.node.sim.filter.domain.DatumSourceTypeInfo;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;
import s10k.node.sim.filter.registry.StandardDatumSourceTypes;

/**
 * Test cases for the {@link DatumSourceTypeRegistry} and
 * {@link StandardDatumSourceTypes} classes.
 *
 * @author matt
 * @version 1.0
 */
public class DatumSourceTypeRegistryTests {

	@Test
	public void standardDatumSourceTypes() {
		// GIVEN
		DatumSourceTypeRegistry registry = DatumSourceTypeRegistry.standard();

		// WHEN
		List<DatumSourceTypeInfo> types = registry.datumSourceTypes(Locale.ENGLISH);

		// THEN
		// @formatter:off
		then(types)
			.as("Types sorted by their names from the message sources, with their capabilities")
			.extracting(DatumSourceTypeInfo::factoryUid, DatumSourceTypeInfo::name,
					DatumSourceTypeInfo::capabilities)
			.containsExactly(
					tuple(StandardDatumSourceTypes.FIXED, "Fixed Data", Set.of()),
					tuple(StandardDatumSourceTypes.MOCK_BATTERY, "Mock Battery",
							Set.of(DatumSourceCapability.Controls, DatumSourceCapability.Instructions)),
					tuple(StandardDatumSourceTypes.MOCK_ENERGY_METER, "Mock Energy Meter", Set.of()),
					tuple(StandardDatumSourceTypes.TOU, "Time-based Tariff Datum Source",
							Set.of(DatumSourceCapability.TariffSchedule)))
			;
		then(types)
			.as("Each type polls every minute by default, like its blueprint")
			.extracting(DatumSourceTypeInfo::defaultSchedule)
			.containsOnly(StandardDatumSourceTypes.DEFAULT_SCHEDULE)
			;
		// @formatter:on
	}

}
