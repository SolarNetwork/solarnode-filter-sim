/* ==================================================================
 * InstructionInfo.java - 18 Oct 2026 8:56:20 am
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
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An instruction issued by a filter.
 *
 * @param topic
 *        the instruction topic, for example {@code SetControlParameter}
 * @param parameters
 *        the instruction parameters
 * @param state
 *        the simulated instruction state, or {@code null}
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record InstructionInfo(String topic, Map<String, String> parameters, @Nullable String state) {

}
