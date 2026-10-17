/* ==================================================================
 * SettingProblem.java - 18 Oct 2026 12:25:01 pm
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

/**
 * A problem applying a setting to a filter.
 *
 * @param key
 *        the setting key
 * @param code
 *        the problem code: {@link SimulationMessage#CODE_UNKNOWN_SETTING} if
 *        the filter has no such setting, or
 *        {@link SimulationMessage#CODE_INVALID_SETTING} if the value could not
 *        be applied
 * @param message
 *        a description of the problem
 * @author matt
 * @version 1.0
 */
public record SettingProblem(String key, String code, String message) {

}
