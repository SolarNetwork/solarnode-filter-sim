/* ==================================================================
 * InstanceIds.java - 18 Oct 2026 1:03:29 pm
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

package s10k.node.sim.filter.csv;

import java.util.Collection;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * SolarNode's rules for filter instance IDs.
 *
 * <p>
 * An instance ID is the {@code <instanceKey>} part of the
 * {@code <factoryUid>.<instanceKey>} keys in a settings CSV file, which
 * SolarNode calls the component instance name. An ID must pass both of
 * SolarNode's checks:
 * </p>
 *
 * <ul>
 * <li>the server's {@code CASettingsService.INSTANCE_UID_ALLOWED} pattern,
 * which the settings CSV import uses;</li>
 * <li>the setup UI's "Add new component" dialog, which allows up to 32
 * characters and checks {@code /^[\p{Letter}\p{Number}_/ -]{1,32}$/u}.</li>
 * </ul>
 *
 * <p>
 * The dialog also rejects the reserved name {@code FACTORY}. An ID cannot start
 * or end with a space either, because SolarNode trims the fields of a settings
 * CSV file, so the instance's {@code .FACTORY} row would not match its
 * settings.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class InstanceIds {

	/** The maximum length of an instance ID. */
	public static final int MAX_LENGTH = 32;

	/** The reserved name that cannot be an instance ID. */
	public static final String RESERVED = "FACTORY";

	/** The server pattern: {@code CASettingsService.INSTANCE_UID_ALLOWED}. */
	public static final Pattern SERVER_PATTERN = Pattern
			.compile("[\\p{IsAlphabetic}\\p{IsDigit}_/ -]{1,32}");

	/** The setup UI pattern, as a Java pattern. */
	public static final Pattern UI_PATTERN = Pattern.compile("[\\p{L}\\p{N}_/ -]{1,32}");

	/**
	 * The instance IDs that SolarNode releases before Core Settings Support
	 * 4.2.1 import: only ASCII letters and digits.
	 */
	private static final Pattern LEGACY_PATTERN = Pattern.compile("[a-zA-Z0-9]+");

	private InstanceIds() {
		// not available
	}

	/**
	 * Check an instance ID.
	 *
	 * @param id
	 *        the instance ID to check
	 * @return a description of the problem, or {@code null} if the ID is
	 *         allowed
	 */
	public static @Nullable String problem(@Nullable String id) {
		if ( id == null || id.isEmpty() ) {
			return "An instance ID is required.";
		}
		if ( id.length() > MAX_LENGTH ) {
			return "An instance ID can have up to %d characters.".formatted(MAX_LENGTH);
		}
		if ( RESERVED.equals(id) ) {
			return "%s is reserved, so it cannot be an instance ID.".formatted(RESERVED);
		}
		if ( !SERVER_PATTERN.matcher(id).matches() || !UI_PATTERN.matcher(id).matches() ) {
			return "An instance ID can have only letter, number, space, dash, underscore, and slash characters.";
		}
		if ( id.startsWith(" ") || id.endsWith(" ") ) {
			return "An instance ID cannot start or end with a space.";
		}
		return null;
	}

	/**
	 * Test if SolarNode releases before Core Settings Support 4.2.1 import the
	 * settings of an instance ID.
	 *
	 * @param id
	 *        the instance ID
	 * @return {@code true} if the ID has only ASCII letters and digits
	 */
	public static boolean isLegacyCompatible(String id) {
		return LEGACY_PATTERN.matcher(id).matches();
	}

	/**
	 * Get the instance ID SolarNode gives a new instance without a name.
	 *
	 * <p>
	 * Like SolarNode's {@code CASettingsService.addProviderFactoryInstance()},
	 * this starts at one more than the number of instances, and counts up until
	 * the number is not in use.
	 * </p>
	 *
	 * @param used
	 *        the instance IDs in use for the filter type
	 * @return the new instance ID
	 */
	public static String next(Collection<String> used) {
		int next = used.size() + 1;
		while ( used.contains(String.valueOf(next)) ) {
			next++;
		}
		return String.valueOf(next);
	}

}
