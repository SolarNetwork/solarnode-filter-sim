/* ==================================================================
 * SettingField.java - 18 Oct 2026 12:25:01 pm
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

package s10k.node.sim.filter.domain;

import java.util.List;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A field of a filter settings form, for one SolarNode setting specifier.
 *
 * <p>
 * The {@code type} decides which other components apply. Components that do not
 * apply to the type are {@code null} or {@code false}.
 * </p>
 *
 * <p>
 * A {@link Type#Group} field with {@code dynamic} set is a list that items can
 * be added to and removed from, which SolarNode shows with {@code +} and
 * {@code -} buttons. Each of its {@code fields} is one item, and the
 * {@code countKey} setting holds the number of items. To add an item, increase
 * the count setting by one. To remove the last item, decrease the count by one
 * and delete every setting whose key starts with {@code key[n]}, where
 * {@code n} is the new count. Then request the form again to get the fields of
 * the changed list.
 * </p>
 *
 * @param type
 *        the field type
 * @param key
 *        the setting key, or {@code null} for a group without one
 * @param label
 *        the label, or {@code null} for a group without a key
 * @param description
 *        the description, as HTML, or {@code null} if there is none
 * @param value
 *        the current value: the setting's value, or its default value if it is
 *        not set; for a toggle it is either {@code trueValue} or
 *        {@code falseValue}, and for a title it is the title text, as HTML if
 *        {@code markup} is set
 * @param defaultValue
 *        the default value, or {@code null} if there is none
 * @param transientSetting
 *        {@code true} if SolarNode does not save the setting, so it is not part
 *        of a settings CSV file; serialized as {@code transient}
 * @param readOnly
 *        {@code true} if the simulator manages the setting, like a chain's list
 *        of filters, so it cannot be edited
 * @param markup
 *        for a title, {@code true} if the value is HTML
 * @param secure
 *        for text, {@code true} if the value is secret, like a password
 * @param relatedServiceFilter
 *        for text, an OSGi filter matching the services whose UID the value can
 *        be, or {@code null}
 * @param options
 *        for a select or radio group, the choices
 * @param trueValue
 *        for a toggle, the "on" value
 * @param falseValue
 *        for a toggle, the "off" value
 * @param min
 *        for a slider, the minimum value
 * @param max
 *        for a slider, the maximum value
 * @param step
 *        for a slider, the step between values
 * @param dynamic
 *        for a group, {@code true} if it is a dynamic list
 * @param countKey
 *        for a dynamic list, the key of the setting that holds the number of
 *        items
 * @param fields
 *        for a group, its fields
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettingField(Type type, @Nullable String key, @Nullable String label,
		@Nullable String description, @Nullable String value, @Nullable String defaultValue,
		@JsonProperty("transient") boolean transientSetting, boolean readOnly, boolean markup,
		boolean secure, @Nullable String relatedServiceFilter, @Nullable List<SettingOption> options,
		@Nullable String trueValue, @Nullable String falseValue, @Nullable Double min,
		@Nullable Double max, @Nullable Double step, boolean dynamic, @Nullable String countKey,
		@Nullable List<SettingField> fields) {

	/**
	 * A setting field type.
	 */
	public enum Type {

		/** Text to show, which is not edited. */
		Title,

		/** A single line of text. */
		Text,

		/** Multiple lines of text. */
		TextArea,

		/** A schedule, as a cron expression or a period. */
		Cron,

		/** An on/off switch. */
		Toggle,

		/** A number within a range. */
		Slider,

		/** One of a set of choices, shown as radio buttons. */
		Radio,

		/** One of a set of choices, shown as a drop-down list. */
		Select,

		/** A group of fields. */
		Group,

	}

	/**
	 * Create a group field.
	 *
	 * @param key
	 *        the setting key, or {@code null} for a group without one
	 * @param label
	 *        the label, or {@code null} for a group without a key
	 * @param description
	 *        the description, as HTML, or {@code null} if there is none
	 * @param readOnly
	 *        {@code true} if the simulator manages the group's settings
	 * @param dynamic
	 *        {@code true} if the group is a dynamic list
	 * @param countKey
	 *        for a dynamic list, the key of the setting that holds the number
	 *        of items
	 * @param fields
	 *        the group's fields
	 * @return the field
	 */
	public static SettingField group(@Nullable String key, @Nullable String label,
			@Nullable String description, boolean readOnly, boolean dynamic, @Nullable String countKey,
			List<SettingField> fields) {
		return new SettingField(Type.Group, key, label, description, null, null, false, readOnly, false,
				false, null, null, null, null, null, null, null, dynamic, countKey, fields);
	}

}
