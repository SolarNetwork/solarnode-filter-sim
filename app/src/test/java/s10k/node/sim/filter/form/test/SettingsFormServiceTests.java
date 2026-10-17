/* ==================================================================
 * SettingsFormServiceTests.java - 18 Oct 2026 12:25:01 pm
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

package s10k.node.sim.filter.form.test;

import static org.assertj.core.api.BDDAssertions.from;
import static org.assertj.core.api.BDDAssertions.then;
import static org.assertj.core.api.BDDAssertions.thenThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.domain.SettingField;
import s10k.node.sim.filter.domain.SettingField.Type;
import s10k.node.sim.filter.domain.SettingOption;
import s10k.node.sim.filter.domain.SettingProblem;
import s10k.node.sim.filter.domain.SettingsForm;
import s10k.node.sim.filter.domain.SettingsFormRequest;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.form.SettingsFormService;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.StandardFilterTypes;
import s10k.node.sim.filter.sim.SimulationException;
import net.solarnetwork.common.expr.spel.SpelExpressionService;

/**
 * Test cases for the {@link SettingsFormService} class.
 *
 * @author matt
 * @version 1.0
 */
public class SettingsFormServiceTests {

	private static final String SPEL = SpelExpressionService.class.getName();

	private SettingsFormService service;

	@BeforeEach
	public void setup() {
		service = new SettingsFormService(new FilterTypeRegistry(StandardFilterTypes.definitions()),
				new SpelExpressionService());
	}

	private SettingsForm form(String factoryUid, String... keysAndValues) {
		Map<String, String> settings = new LinkedHashMap<>();
		for ( int i = 0; i < keysAndValues.length; i += 2 ) {
			settings.put(keysAndValues[i], keysAndValues[i + 1]);
		}
		return service.form(new SettingsFormRequest(factoryUid, settings), Locale.ENGLISH);
	}

	/**
	 * Find a field by key, searching groups too.
	 */
	private static SettingField field(List<SettingField> fields, String key) {
		for ( SettingField f : fields ) {
			if ( key.equals(f.key()) ) {
				return f;
			}
			if ( f.fields() != null ) {
				SettingField result = field(f.fields(), key);
				if ( result != null ) {
					return result;
				}
			}
		}
		return null;
	}

	/**
	 * Get the keys of all fields, searching groups too.
	 */
	private static List<String> keys(List<SettingField> fields) {
		List<String> result = new ArrayList<>();
		for ( SettingField f : fields ) {
			if ( f.key() != null ) {
				result.add(f.key());
			}
			if ( f.fields() != null ) {
				result.addAll(keys(f.fields()));
			}
		}
		return result;
	}

	@Test
	public void expression() {
		// WHEN
		SettingsForm form = form(StandardFilterTypes.EXPRESSION, "uid", "EXP", "expressionConfigsCount",
				"1", "expressionConfigs[0].name", "kw", "expressionConfigs[0].expression",
				"watts / 1000.0");

		// THEN
		// @formatter:off
		then(form.fields())
			.as("Status left out, then the base filter settings, then the expressions list")
			.extracting(SettingField::key)
			.containsExactly("uid", "groupUid", "sourceId", "requiredOperationalMode", "requiredTag",
					"expressionConfigs")
			;
		then(form.fields().getFirst())
			.as("Value from settings")
			.returns("Service Name", from(SettingField::label))
			.returns("EXP", from(SettingField::value))
			.returns("", from(SettingField::defaultValue))
			;
		then(form.problems())
			.as("All settings applied")
			.isEmpty()
			;

		SettingField list = form.fields().getLast();
		then(list)
			.as("Expressions dynamic list")
			.returns(Type.Group, from(SettingField::type))
			.returns("Expressions", from(SettingField::label))
			.returns(true, from(SettingField::dynamic))
			.returns("expressionConfigsCount", from(SettingField::countKey))
			;
		then(list.fields())
			.as("One list item")
			.hasSize(1)
			.first()
			.as("Item is a group without a key")
			.returns(Type.Group, from(SettingField::type))
			.returns(null, from(SettingField::key))
			;
		then(list.fields().getFirst().fields())
			.as("Item labels from the Item messages, with values from settings or defaults")
			.extracting(SettingField::key, SettingField::type, SettingField::label, SettingField::value)
			.containsExactly(
					tuple("expressionConfigs[0].name", Type.Text, "Property", "kw"),
					tuple("expressionConfigs[0].datumPropertyTypeKey", Type.Select, "Property Type", "i"),
					tuple("expressionConfigs[0].expression", Type.TextArea, "Expression", "watts / 1000.0"),
					tuple("expressionConfigs[0].expressionServiceId", Type.Select, "Expression Language", ""))
			;
		then(field(form.fields(), "expressionConfigs[0].expressionServiceId").options())
			.as("Expression language choices include none and the injected expression service")
			.extracting(SettingOption::value)
			.containsExactly("", SPEL)
			;
		then(field(form.fields(), "expressionConfigs[0].expression").description())
			.as("Description formatted with the setting's description arguments")
			.contains("Expression-Languages")
			.doesNotContain("{0}")
			;
		// @formatter:on
	}

	@Test
	public void problems() {
		// WHEN
		SettingsForm form = form(StandardFilterTypes.THROTTLE, "frequencySeconds", "abc", "foo", "bar");

		// THEN
		// @formatter:off
		then(form.problems())
			.as("Problems reported for the invalid and unknown settings")
			.extracting(SettingProblem::key, SettingProblem::code)
			.containsExactlyInAnyOrder(
					tuple("frequencySeconds", SimulationMessage.CODE_INVALID_SETTING),
					tuple("foo", SimulationMessage.CODE_UNKNOWN_SETTING))
			;
		then(field(form.fields(), "frequencySeconds"))
			.as("Invalid value still shown")
			.returns("abc", from(SettingField::value))
			;
		// @formatter:on
	}

	@Test
	public void toggle() {
		// WHEN
		SettingsForm form = form(StandardFilterTypes.SPLIT, "swallowInput", "0", "persist", "yes");

		// THEN
		// @formatter:off
		then(field(form.fields(), "swallowInput"))
			.as("Toggle value normalized to its off value")
			.returns(Type.Toggle, from(SettingField::type))
			.returns("false", from(SettingField::value))
			.returns("true", from(SettingField::defaultValue))
			.returns("true", from(SettingField::trueValue))
			.returns("false", from(SettingField::falseValue))
			;
		then(field(form.fields(), "persist"))
			.as("Toggle value normalized to its on value")
			.returns("true", from(SettingField::value))
			;
		then(field(form.fields(), "propertySourceMappings[0].replacement"))
			.as("No list items without a count")
			.isNull()
			;
		// @formatter:on
	}

	@Test
	public void unformattedDescription() {
		// WHEN
		SettingsForm form = form(StandardFilterTypes.SPLIT, "propertySourceMappingsCount", "1");

		// THEN
		// @formatter:off
		then(field(form.fields(), "propertySourceMappings[0].replacement").description())
			.as("Description without arguments not formatted, so its braces are kept")
			.contains("{p}", "{1}")
			;
		// @formatter:on
	}

	@Test
	public void chain() {
		// WHEN
		SettingsForm form = form(StandardFilterTypes.CHAIN, "uid", "Chain - Main", "transformUidsCount",
				"2", "transformUids[0]", "EXP", "transformUids[1]", "THROTTLE");

		// THEN
		// @formatter:off
		SettingField filters = field(form.fields(), "transformUids");
		then(filters)
			.as("Chain filter list is read-only, because the simulator manages it")
			.returns(true, from(SettingField::readOnly))
			.returns(true, from(SettingField::dynamic))
			;
		then(filters.fields())
			.as("Filter labels number the filters from 1")
			.extracting(SettingField::label, SettingField::value, SettingField::readOnly,
					SettingField::relatedServiceFilter)
			.containsExactly(
					tuple("1", "EXP", true,
							"(&(objectClass=net.solarnetwork.service.DatumFilterService)(role=user))"),
					tuple("2", "THROTTLE", true,
							"(&(objectClass=net.solarnetwork.service.DatumFilterService)(role=user))"))
			;
		// @formatter:on
	}

	@Test
	public void nestedLists() {
		// WHEN
		SettingsForm form = form(StandardFilterTypes.INSTRUCTOR, "instructorConfigsCount", "1",
				"instructorConfigs[0].instructionsCount", "1",
				"instructorConfigs[0].instructions[0].parametersCount", "2");

		// THEN
		// @formatter:off
		then(field(form.fields(), "instructorConfigs[0].instructions[0].parameters"))
			.as("Nested dynamic list")
			.returns("Instruction Parameters", from(SettingField::label))
			.returns(true, from(SettingField::dynamic))
			.returns("instructorConfigs[0].instructions[0].parametersCount",
					from(SettingField::countKey))
			;
		then(field(form.fields(), "instructorConfigs[0].instructions[0].parameters[1].name"))
			.as("Nested list item label from the Item message")
			.returns("Parameter Name", from(SettingField::label))
			;
		// @formatter:on
	}

	@Test
	public void statusLeftOut() {
		// WHEN
		SettingsForm form = form(StandardFilterTypes.DOWNSAMPLE, "sampleDurationSecs", "60");

		// THEN
		// @formatter:off
		then(keys(form.fields()))
			.as("Runtime status titles left out")
			.doesNotContain("status")
			.startsWith("uid")
			;
		then(form.limitation())
			.as("Simulation limitation for the settings")
			.contains("Sample Duration")
			;
		// @formatter:on
	}

	@Test
	public void transientSetting() {
		// WHEN
		SettingsForm form = form(StandardFilterTypes.VIRTUAL_METER, "virtualMeterConfigsCount", "1",
				"virtualMeterConfigs[0].config", "1000");

		// THEN
		// @formatter:off
		then(field(form.fields(), "virtualMeterConfigs[0].config"))
			.as("Transient setting flagged, with its value from settings")
			.returns("Reading Value", from(SettingField::label))
			.returns(true, from(SettingField::transientSetting))
			.returns("1000", from(SettingField::value))
			;
		then(field(form.fields(), "virtualMeterConfigs[0].name"))
			.as("Other settings are not transient")
			.returns(false, from(SettingField::transientSetting))
			;
		// @formatter:on
	}

	@Test
	public void unsupportedFilterType() {
		// THEN
		// @formatter:off
		thenThrownBy(() -> form("net.solarnetwork.node.datum.filter.tariff"))
			.as("Unsupported filter type")
			.isInstanceOf(SimulationException.class)
			.hasMessageContaining("not supported")
			;
		// @formatter:on
	}

}
