/* ==================================================================
 * ControlInfo.java - 25 Oct 2026 10:12:40 am
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

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The state of a simulated control.
 *
 * @param controlId
 *        the control ID
 * @param type
 *        the control type name
 * @param value
 *        the current value, or {@code null} if the control has none
 * @param initialValue
 *        the value when the simulation started, or {@code null} if the control
 *        had none
 * @param modified
 *        the time the value was last set, or {@code null} if it was not set
 * @param updates
 *        the number of times the value was set
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ControlInfo(String controlId, String type, @Nullable String value,
		@Nullable String initialValue, @Nullable Instant modified, int updates) {

}
