/* ==================================================================
 * DatumSourceSettings.java - 30 Oct 2026 10:12:31 am
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

import static s10k.node.sim.filter.domain.SimulationMessage.info;
import static s10k.node.sim.filter.domain.SimulationMessage.warning;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.DatumSourceConfig;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.registry.DatumSourceTypeDefinition;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;

/**
 * The simulated datum sources of a settings CSV file's datum source component
 * settings.
 *
 * <p>
 * Each instance of a supported datum source type is a datum source with its
 * settings, whose controls (the Mock Battery's) publish their changes, not
 * persisted. The SolarNode 1.x setting keys, which a node still applies through
 * its managed job, are changed to the current ones, so the settings form shows
 * them: {@code triggerCronExpression} is {@code schedule},
 * {@code jobDetail.jobDataMap['datumDataSource'].x} is
 * {@code jobService.datumDataSource.x}, and {@code jobDetail.x} is {@code x}. A
 * current key wins over a 1.x key for the same setting, as the node ignores
 * {@code triggerCronExpression} once {@code schedule} is set.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
final class DatumSourceSettings {

	/** The datum source types. */
	private static final DatumSourceTypeRegistry TYPES = DatumSourceTypeRegistry.standard();

	/** The factory UIDs of the components this class reads. */
	static final List<String> FACTORY_UIDS = TYPES.definitions().stream()
			.map(DatumSourceTypeDefinition::factoryUid).toList();

	private static final String JOB_DETAIL_PREFIX = "jobDetail.";

	private static final String LEGACY_SCHEDULE = "triggerCronExpression";

	private static final String SCHEDULE = "schedule";

	/** A 1.x job data map key, after {@link #JOB_DETAIL_PREFIX}. */
	private static final Pattern JOB_DATA_MAP = Pattern
			.compile("^jobDataMap\\[(['\"]?)([^'\"\\]]+)\\1\\](.*)$");

	/**
	 * The result.
	 *
	 * @param datumSources
	 *        the datum sources
	 * @param messages
	 *        messages about them
	 */
	record Result(List<DatumSourceConfig> datumSources, List<SimulationMessage> messages) {

	}

	private DatumSourceSettings() {
		// not available
	}

	/**
	 * Get the factory UID of a key of one of the {@link #FACTORY_UIDS}
	 * instances.
	 *
	 * @param key
	 *        the setting key
	 * @return the factory UID, or {@code null} if the key is not
	 *         {@code <factoryUid>.<instanceKey>} for one of them
	 */
	static @Nullable String factoryUid(String key) {
		String result = null;
		for ( String uid : FACTORY_UIDS ) {
			if ( key.length() > uid.length() + 1 && key.startsWith(uid)
					&& key.charAt(uid.length()) == '.'
					&& (result == null || uid.length() > result.length()) ) {
				result = uid;
			}
		}
		return result;
	}

	/**
	 * Get the datum sources of component instances.
	 *
	 * @param instances
	 *        the instances, in file order
	 * @return the result
	 */
	static Result of(List<ControlSettings.Instance> instances) {
		final List<SimulationMessage> messages = new ArrayList<>(2);
		final List<DatumSourceConfig> result = new ArrayList<>(instances.size());
		for ( ControlSettings.Instance instance : instances ) {
			final String label = label(instance);
			if ( !instance.factoryRow() ) {
				messages.add(warning(SimulationMessage.CODE_MISSING_FACTORY,
						"The file has no %s%s row for the %s, so SolarNode would not create it. The simulator imported it anyway."
								.formatted(instance.factoryUid(), SettingsCsv.FACTORY_SUFFIX, label),
						null, null));
			}
			final Map<String, String> settings = new LinkedHashMap<>(instance.settings().size());
			final Map<String, String> legacy = new LinkedHashMap<>(2);
			for ( Map.Entry<String, String> e : instance.settings().entrySet() ) {
				final String key = currentKey(e.getKey());
				if ( key == null ) {
					settings.put(e.getKey(), e.getValue());
				} else {
					legacy.put(key, e.getValue());
				}
			}
			if ( !legacy.isEmpty() ) {
				legacy.forEach(settings::putIfAbsent);
				messages.add(info(SimulationMessage.CODE_LEGACY_SETTINGS,
						"The %s has SolarNode 1.x setting keys, which the simulator changed to the current ones."
								.formatted(label),
						null));
			}
			result.add(new DatumSourceConfig(null, instance.factoryUid(), instance.instanceKey(),
					settings));
		}
		return new Result(result, messages);
	}

	/**
	 * Get the current key of a SolarNode 1.x setting key.
	 *
	 * @param key
	 *        the key
	 * @return the current key, or {@code null} if {@code key} is not a 1.x key
	 */
	private static @Nullable String currentKey(String key) {
		if ( LEGACY_SCHEDULE.equals(key) ) {
			return SCHEDULE;
		}
		if ( !key.startsWith(JOB_DETAIL_PREFIX) ) {
			return null;
		}
		final String rest = key.substring(JOB_DETAIL_PREFIX.length());
		final Matcher m = JOB_DATA_MAP.matcher(rest);
		if ( m.matches() ) {
			return "jobService." + m.group(2) + m.group(3);
		}
		final String current = currentKey(rest);
		return (current != null ? current : rest);
	}

	private static String label(ControlSettings.Instance instance) {
		final DatumSourceTypeDefinition type = TYPES.definition(instance.factoryUid());
		final String name = (type != null ? type.name(Locale.getDefault()) : "datum source");
		return name + ' ' + SimulationMessage.quoted(instance.instanceKey());
	}

}
