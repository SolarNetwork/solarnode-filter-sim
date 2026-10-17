/* ==================================================================
 * SettingFieldRenderer.java - 18 Oct 2026 12:25:01 pm
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

package s10k.node.sim.filter.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.context.MessageSource;
import s10k.node.sim.filter.domain.SettingField;
import s10k.node.sim.filter.domain.SettingOption;
import s10k.node.sim.filter.settings.SettingsApplier;
import net.solarnetwork.settings.CronExpressionSettingSpecifier;
import net.solarnetwork.settings.GroupSettingSpecifier;
import net.solarnetwork.settings.KeyedSettingSpecifier;
import net.solarnetwork.settings.MultiValueSettingSpecifier;
import net.solarnetwork.settings.RadioGroupSettingSpecifier;
import net.solarnetwork.settings.SettingSpecifier;
import net.solarnetwork.settings.SliderSettingSpecifier;
import net.solarnetwork.settings.TextAreaSettingSpecifier;
import net.solarnetwork.settings.TextFieldSettingSpecifier;
import net.solarnetwork.settings.TitleSettingSpecifier;
import net.solarnetwork.settings.ToggleSettingSpecifier;
import net.solarnetwork.util.StringUtils;

/**
 * Turns SolarNode setting specifiers into settings form fields.
 *
 * <p>
 * This follows how the SolarNode setup UI renders settings, in its
 * {@code settings/setting-control.html} template and {@code SolarNodeUtils}
 * helper:
 * </p>
 *
 * <ul>
 * <li>A setting's label is its {@code <key>.key} message, and its description
 * is its {@code <key>.desc} message, formatted with the setting's description
 * arguments. When a message is missing, the key is tried again with every
 * {@code [n]} index replaced by {@code Item}. For a label, the setting's
 * position in its keyed group, counting from 1, is then the first message
 * argument. A missing label falls back to the key.</li>
 * <li>A setting's value is its value in the settings, or else its default
 * value. SolarNode always shows the default value of a transient setting, but
 * the simulator applies transient settings when it creates the filter, so their
 * values are shown too.</li>
 * <li>Settings that are neither keyed nor groups are not shown.</li>
 * </ul>
 *
 * <p>
 * The runtime status title that SolarNode filters show first is left out,
 * because the form is rendered from a filter that has not processed any datum.
 * A filter chain's list of filters is read-only, because the simulator manages
 * it.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
final class SettingFieldRenderer {

	/** The key of the runtime status title SolarNode filters show first. */
	static final String STATUS_KEY = "status";

	/** The key of a filter chain's list of filters. */
	static final String CHAIN_FILTERS_KEY = "transformUids";

	private static final String LABEL_SUFFIX = ".key";

	private static final String DESCRIPTION_SUFFIX = ".desc";

	private static final Pattern INDEX_PATTERN = Pattern.compile("\\[\\d+\\]");

	private final MessageSource messageSource;
	private final Map<String, String> settings;
	private final Locale locale;
	private final boolean chain;

	/**
	 * Constructor.
	 *
	 * @param messageSource
	 *        the filter's message source
	 * @param settings
	 *        the filter settings
	 * @param locale
	 *        the locale to use
	 * @param chain
	 *        {@code true} if the filter is a filter chain
	 */
	SettingFieldRenderer(MessageSource messageSource, Map<String, String> settings, Locale locale,
			boolean chain) {
		super();
		this.messageSource = messageSource;
		this.settings = settings;
		this.locale = locale;
		this.chain = chain;
	}

	/**
	 * Render setting specifiers as form fields.
	 *
	 * @param specifiers
	 *        the filter's setting specifiers
	 * @return the fields
	 */
	List<SettingField> render(List<SettingSpecifier> specifiers) {
		final List<SettingField> result = new ArrayList<>(specifiers.size());
		for ( SettingSpecifier s : specifiers ) {
			if ( isStatus(s) ) {
				continue;
			}
			SettingField field = field(s, null, false);
			if ( field != null ) {
				result.add(field);
			}
		}
		return result;
	}

	private static boolean isStatus(SettingSpecifier s) {
		return (s instanceof TitleSettingSpecifier title && !(s instanceof TextFieldSettingSpecifier)
				&& STATUS_KEY.equals(title.getKey()));
	}

	private @Nullable SettingField field(SettingSpecifier s, @Nullable Integer index, boolean readOnly) {
		if ( s instanceof GroupSettingSpecifier group ) {
			return group(group, index, readOnly);
		}
		if ( s instanceof KeyedSettingSpecifier<?> keyed ) {
			return keyed(keyed, index, readOnly);
		}
		// like SolarNode, other settings are not shown
		return null;
	}

	private @Nullable SettingField group(GroupSettingSpecifier group, @Nullable Integer index,
			boolean readOnly) {
		final String key = (group.getKey() != null && !group.getKey().isEmpty() ? group.getKey() : null);
		final boolean managed = (readOnly || (chain && CHAIN_FILTERS_KEY.equals(key)));
		final List<SettingSpecifier> children = group.getGroupSettings();
		final List<SettingField> fields = new ArrayList<>(children != null ? children.size() : 0);
		if ( children != null ) {
			int position = 0;
			for ( SettingSpecifier child : children ) {
				position++;
				// a keyed group numbers its settings for their labels; a group without a key
				// passes on the number it was given
				SettingField field = field(child, key != null ? position : index, managed);
				if ( field != null ) {
					fields.add(field);
				}
			}
		}
		if ( key == null ) {
			// like SolarNode, a group without a key is never a dynamic list
			return (fields.isEmpty() ? null
					: SettingField.group(null, null, null, managed, false, null, fields));
		}
		return SettingField.group(key, label(key, null), description(key, null), managed,
				group.isDynamic(), group.isDynamic() ? key + SettingsApplier.COUNT_SUFFIX : null,
				fields);
	}

	private SettingField keyed(KeyedSettingSpecifier<?> s, @Nullable Integer index, boolean readOnly) {
		final String key = s.getKey();
		final Object rawValue = rawValue(s);
		SettingField.Type type = SettingField.Type.Text;
		String value = string(rawValue);
		String defaultValue = string(s.getDefaultValue());
		boolean markup = false;
		boolean secure = false;
		String relatedServiceFilter = null;
		List<SettingOption> options = null;
		String trueValue = null;
		String falseValue = null;
		Double min = null;
		Double max = null;
		Double step = null;
		if ( s instanceof SliderSettingSpecifier slider ) {
			type = SettingField.Type.Slider;
			min = slider.getMinimumValue();
			max = slider.getMaximumValue();
			step = slider.getStep();
		} else if ( s instanceof ToggleSettingSpecifier toggle ) {
			type = SettingField.Type.Toggle;
			trueValue = string(toggle.getTrueValue());
			falseValue = string(toggle.getFalseValue());
			value = (isToggleOn(rawValue, toggle) ? trueValue : falseValue);
			defaultValue = (isToggleOn(s.getDefaultValue(), toggle) ? trueValue : falseValue);
		} else if ( s instanceof TextAreaSettingSpecifier ) {
			type = SettingField.Type.TextArea;
		} else if ( s instanceof RadioGroupSettingSpecifier radio ) {
			type = SettingField.Type.Radio;
			options = options(radio, true);
		} else if ( s instanceof MultiValueSettingSpecifier select ) {
			type = SettingField.Type.Select;
			options = options(select, false);
		} else if ( s instanceof CronExpressionSettingSpecifier ) {
			type = SettingField.Type.Cron;
		} else if ( s instanceof TextFieldSettingSpecifier text ) {
			secure = text.isSecureTextEntry();
			relatedServiceFilter = text.getRelatedServiceFilter();
		} else if ( s instanceof TitleSettingSpecifier title ) {
			type = SettingField.Type.Title;
			markup = title.isMarkup();
			defaultValue = null;
		}
		// other keyed settings, like SolarNode's location lookups, are edited as text
		return new SettingField(type, key, label(key, index),
				description(key, s.getDescriptionArguments()), value, defaultValue, s.isTransient(),
				readOnly, markup, secure, relatedServiceFilter, options, trueValue, falseValue, min, max,
				step, false, null, null);
	}

	private @Nullable Object rawValue(KeyedSettingSpecifier<?> s) {
		final String value = settings.get(s.getKey());
		return (value != null ? value : s.getDefaultValue());
	}

	/**
	 * Test if a toggle value is "on", like SolarNode's
	 * {@code SolarNodeUtils.isToggleSettingValueTrue()}.
	 *
	 * @param value
	 *        the value
	 * @param toggle
	 *        the toggle
	 * @return {@code true} if the value is the toggle's "on" value
	 */
	private static boolean isToggleOn(@Nullable Object value, ToggleSettingSpecifier toggle) {
		if ( value == null ) {
			return false;
		}
		final Object trueValue = toggle.getTrueValue();
		if ( trueValue instanceof Boolean on ) {
			if ( value instanceof Boolean b ) {
				return b.equals(on);
			}
			return StringUtils.parseBoolean(value.toString()) == on;
		}
		return (trueValue != null && value.toString().equals(trueValue.toString()));
	}

	private @Nullable List<SettingOption> options(TitleSettingSpecifier s, boolean describe) {
		final Map<String, String> titles = s.getValueTitles();
		if ( titles == null || titles.isEmpty() ) {
			return null;
		}
		final List<SettingOption> result = new ArrayList<>(titles.size());
		for ( Map.Entry<String, String> e : titles.entrySet() ) {
			final String value = (e.getKey() != null ? e.getKey() : "");
			final String title = e.getValue();
			result.add(new SettingOption(value, title != null ? title : value,
					describe ? description(value, null) : null));
		}
		return result;
	}

	private String label(String key, @Nullable Integer index) {
		final String code = key + LABEL_SUFFIX;
		String result = message(code, null);
		if ( result == null ) {
			final String itemCode = itemCode(code);
			if ( itemCode != null ) {
				result = message(itemCode, index != null ? new Object[] { index } : null);
			}
		}
		return (result != null ? result : key);
	}

	private @Nullable String description(String key, Object @Nullable [] arguments) {
		final String code = key + DESCRIPTION_SUFFIX;
		String result = message(code, arguments);
		if ( result == null ) {
			final String itemCode = itemCode(code);
			if ( itemCode != null ) {
				result = message(itemCode, arguments);
			}
		}
		return result;
	}

	/**
	 * Get a message code with every {@code [n]} index replaced by {@code Item}.
	 *
	 * @param code
	 *        the message code
	 * @return the item message code, or {@code null} if {@code code} has no
	 *         indexes
	 */
	private static @Nullable String itemCode(String code) {
		final String result = INDEX_PATTERN.matcher(code).replaceAll("Item");
		return (result.equals(code) ? null : result);
	}

	private @Nullable String message(String code, Object @Nullable [] arguments) {
		try {
			return messageSource.getMessage(code, arguments, null, locale);
		} catch ( IllegalArgumentException e ) {
			// the message is not a valid MessageFormat pattern, for example it contains a
			// literal {p}, so use it unformatted
			return messageSource.getMessage(code, null, null, locale);
		}
	}

	private static @Nullable String string(@Nullable Object o) {
		return (o != null ? o.toString() : null);
	}

}
