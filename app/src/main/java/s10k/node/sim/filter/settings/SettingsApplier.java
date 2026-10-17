/* ==================================================================
 * SettingsApplier.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.settings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeansException;
import org.springframework.beans.PropertyAccessorFactory;

/**
 * Apply SolarNode settings to a component, the way SolarNode does.
 *
 * <p>
 * SolarNode applies a component's settings with Gemini Blueprint's
 * {@code CMUtils.applyMapOntoInstance()}: a Spring {@link BeanWrapper} with
 * {@code autoGrowNestedPaths} enabled sets every writable property. Indexed
 * settings like {@code expressionConfigs[0].expression} work along with list
 * size settings like {@code expressionConfigsCount}.
 * </p>
 *
 * <p>
 * SolarNode applies settings in no particular order. This class orders them by
 * {@link #SETTING_KEY_ORDER}, so that list size settings come before the list
 * items, and the results are repeatable.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class SettingsApplier {

	/** The suffix of list size setting keys. */
	public static final String COUNT_SUFFIX = "Count";

	/**
	 * The order settings are applied in: by nesting depth, then list size
	 * settings first, then by key with numbers compared by value.
	 */
	public static final Comparator<String> SETTING_KEY_ORDER = Comparator
			.comparingInt(SettingsApplier::depth)
			.thenComparingInt((String k) -> k.endsWith(COUNT_SUFFIX) ? 0 : 1)
			.thenComparing(SettingsApplier::compareNatural);

	private SettingsApplier() {
		// not available
	}

	/**
	 * A problem applying a setting.
	 *
	 * @param key
	 *        the setting key
	 * @param message
	 *        the problem
	 * @param unknown
	 *        {@code true} if the component has no writable property for the
	 *        setting, or {@code false} if the value could not be applied
	 */
	public record Problem(String key, String message, boolean unknown) {

	}

	/**
	 * Apply settings to a component.
	 *
	 * @param component
	 *        the component to apply the settings to
	 * @param settings
	 *        the settings to apply
	 * @return any problems applying the settings, never {@code null}
	 */
	public static List<Problem> apply(Object component, Map<String, String> settings) {
		final List<Problem> problems = new ArrayList<>(2);
		if ( settings.isEmpty() ) {
			return problems;
		}
		final BeanWrapper bean = PropertyAccessorFactory.forBeanPropertyAccess(component);
		bean.setAutoGrowNestedPaths(true);
		for ( String key : settings.keySet().stream().sorted(SETTING_KEY_ORDER).toList() ) {
			try {
				if ( !bean.isWritableProperty(key) ) {
					problems.add(new Problem(key, "Unknown setting.", true));
					continue;
				}
				bean.setPropertyValue(key, settings.get(key));
			} catch ( BeansException e ) {
				Throwable root = e.getMostSpecificCause();
				problems.add(new Problem(key,
						root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName(),
						false));
			}
		}
		return problems;
	}

	/**
	 * Get the nesting depth of a setting key: the number of property path
	 * separators ({@code .} or {@code [}) in the key.
	 *
	 * @param key
	 *        the key
	 * @return the depth
	 */
	public static int depth(String key) {
		int depth = 0;
		for ( int i = 0, len = key.length(); i < len; i++ ) {
			char c = key.charAt(i);
			if ( c == '.' || c == '[' ) {
				depth++;
			}
		}
		return depth;
	}

	/**
	 * Compare strings, comparing runs of digits by their numeric value.
	 *
	 * @param a
	 *        the first string
	 * @param b
	 *        the second string
	 * @return the comparison result
	 */
	static int compareNatural(String a, String b) {
		int i = 0;
		int j = 0;
		final int aLen = a.length();
		final int bLen = b.length();
		while ( i < aLen && j < bLen ) {
			final char ca = a.charAt(i);
			final char cb = b.charAt(j);
			if ( Character.isDigit(ca) && Character.isDigit(cb) ) {
				int iEnd = i;
				while ( iEnd < aLen && Character.isDigit(a.charAt(iEnd)) ) {
					iEnd++;
				}
				int jEnd = j;
				while ( jEnd < bLen && Character.isDigit(b.charAt(jEnd)) ) {
					jEnd++;
				}
				String na = a.substring(i, iEnd).replaceFirst("^0+(?=.)", "");
				String nb = b.substring(j, jEnd).replaceFirst("^0+(?=.)", "");
				int cmp = (na.length() != nb.length() ? Integer.compare(na.length(), nb.length())
						: na.compareTo(nb));
				if ( cmp != 0 ) {
					return cmp;
				}
				i = iEnd;
				j = jEnd;
			} else {
				if ( ca != cb ) {
					return Character.compare(ca, cb);
				}
				i++;
				j++;
			}
		}
		return Integer.compare(aLen - i, bLen - j);
	}

}
