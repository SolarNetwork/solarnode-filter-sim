/* ==================================================================
 * SettingsApplierTests.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.settings.test;

import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.settings.SettingsApplier;
import net.solarnetwork.node.datum.filter.expr.ExpressionDatumFilterService;
import net.solarnetwork.node.datum.filter.expr.ExpressionTransformConfig;

/**
 * Test cases for the {@link SettingsApplier} class.
 *
 * @author matt
 * @version 1.0
 */
public class SettingsApplierTests {

	@Test
	public void keyOrder() {
		// GIVEN
		List<String> keys = List.of("expressionConfigs[10].name", "uid", "expressionConfigs[2].name",
				"expressionConfigsCount", "expressionConfigs[2].expression");

		// WHEN
		List<String> result = keys.stream().sorted(SettingsApplier.SETTING_KEY_ORDER).toList();

		// THEN
		// @formatter:off
		then(result)
			.as("Top-level keys first, list sizes first, then items in numeric order")
			.containsExactly(
					"expressionConfigsCount",
					"uid",
					"expressionConfigs[2].expression",
					"expressionConfigs[2].name",
					"expressionConfigs[10].name")
			;
		// @formatter:on
	}

	@Test
	public void apply() {
		// GIVEN
		ExpressionDatumFilterService filter = new ExpressionDatumFilterService();
		Map<String, String> settings = new LinkedHashMap<>();
		settings.put("expressionConfigs[1].name", "b");
		settings.put("expressionConfigs[0].name", "a");
		settings.put("expressionConfigs[0].expression", "1 + 1");
		settings.put("expressionConfigsCount", "2");
		settings.put("uid", "Test");

		// WHEN
		List<SettingsApplier.Problem> problems = SettingsApplier.apply(filter, settings);

		// THEN
		// @formatter:off
		then(problems)
			.as("No problems applying settings")
			.isEmpty()
			;
		then(filter)
			.as("Service Name applied")
			.returns("Test", from(ExpressionDatumFilterService::getUid))
			;
		then(filter.getExpressionConfigs())
			.as("List items created and configured")
			.extracting(ExpressionTransformConfig::getName)
			.containsExactly("a", "b")
			;
		then(filter.getExpressionConfigs()[0])
			.as("Nested property applied")
			.returns("1 + 1", from(ExpressionTransformConfig::getExpression))
			;
		// @formatter:on
	}

	@Test
	public void apply_problems() {
		// GIVEN
		ExpressionDatumFilterService filter = new ExpressionDatumFilterService();
		Map<String, String> settings = new LinkedHashMap<>();
		settings.put("notASetting", "foo");
		settings.put("expressionConfigsCount", "many");

		// WHEN
		List<SettingsApplier.Problem> problems = SettingsApplier.apply(filter, settings);

		// THEN
		// @formatter:off
		then(problems)
			.as("Problem reported for each setting")
			.extracting(SettingsApplier.Problem::key, SettingsApplier.Problem::unknown)
			.containsExactlyInAnyOrder(
					org.assertj.core.groups.Tuple.tuple("notASetting", true),
					org.assertj.core.groups.Tuple.tuple("expressionConfigsCount", false))
			;
		// @formatter:on
	}

}
