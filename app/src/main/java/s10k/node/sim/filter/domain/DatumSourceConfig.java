/* ==================================================================
 * DatumSourceConfig.java - 29 Oct 2026 6:40:00 pm
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

import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A simulated SolarNode datum source, which the simulation polls on its
 * schedule.
 *
 * @param id
 *        the client ID of the datum source, unique in the request
 * @param factoryUid
 *        the SolarNode factory UID of the datum source type, for example
 *        {@code net.solarnetwork.node.datum.energymeter.mock}
 * @param instanceKey
 *        the SolarNode instance ID, or {@code null}
 * @param settings
 *        the SolarNode settings of the datum source's job, for example
 *        {@code schedule} and {@code jobService.datumDataSource.sourceId}
 * @param publish
 *        when the datum source's controls publish a datum, or {@code null} for
 *        {@link ControlPublishMode#Change}; as datum sources post only changes,
 *        any mode but {@code Never} publishes each one
 * @param persist
 *        {@code true} to persist the datum the datum source's controls publish,
 *        or {@code null} for {@code false}
 * @author matt
 * @version 1.0
 */
public record DatumSourceConfig(@Nullable String id, @Nullable String factoryUid,
		@Nullable String instanceKey, @Nullable Map<String, String> settings,
		@Nullable ControlPublishMode publish, @Nullable Boolean persist) {

	/**
	 * Constructor, for a datum source whose controls publish their changes, not
	 * persisted.
	 *
	 * @param id
	 *        the client ID
	 * @param factoryUid
	 *        the factory UID
	 * @param instanceKey
	 *        the instance ID
	 * @param settings
	 *        the settings
	 */
	public DatumSourceConfig(@Nullable String id, @Nullable String factoryUid,
			@Nullable String instanceKey, @Nullable Map<String, String> settings) {
		this(id, factoryUid, instanceKey, settings, null, null);
	}

	/**
	 * Get the settings.
	 *
	 * @return the settings, never {@code null}
	 */
	public Map<String, String> settingsOrEmpty() {
		return (settings != null ? settings : Map.of());
	}

	/**
	 * Get when the datum source's controls publish a datum.
	 *
	 * @return the publish mode, never {@code null}
	 */
	public ControlPublishMode publishValue() {
		return (publish != null ? publish : ControlPublishMode.Change);
	}

	/**
	 * Get the persist mode of the datum the datum source's controls publish.
	 *
	 * @return {@code true} to persist them
	 */
	public boolean persistValue() {
		return (persist != null && persist);
	}

}
