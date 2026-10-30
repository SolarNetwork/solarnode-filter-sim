/* ==================================================================
 * SettingsForm.java - 18 Oct 2026 12:25:01 pm
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

/**
 * The settings form of a filter or datum source: its settings, as SolarNode
 * presents them.
 *
 * @param factoryUid
 *        the SolarNode component factory UID of the filter or datum source type
 * @param fields
 *        the form fields, in display order
 * @param problems
 *        problems applying the settings, such as unknown setting keys
 * @param limitation
 *        how the simulation of the filter or datum source, with these settings,
 *        differs from SolarNode, or {@code null} if there are no known
 *        differences
 * @param controlIds
 *        for a datum source that provides controls, the control IDs it provides
 *        with these settings, with any placeholders resolved; else {@code null}
 * @author matt
 * @version 1.1
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SettingsForm(String factoryUid, List<SettingField> fields, List<SettingProblem> problems,
		@Nullable String limitation, @Nullable List<String> controlIds) {

	/**
	 * Constructor, for a form without controls.
	 *
	 * @param factoryUid
	 *        the factory UID
	 * @param fields
	 *        the fields
	 * @param problems
	 *        the problems
	 * @param limitation
	 *        the limitation, or {@code null}
	 */
	public SettingsForm(String factoryUid, List<SettingField> fields, List<SettingProblem> problems,
			@Nullable String limitation) {
		this(factoryUid, fields, problems, limitation, null);
	}

}
