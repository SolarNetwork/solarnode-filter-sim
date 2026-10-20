/* ==================================================================
 * CommandInfo.java - 20 Oct 2026 3:33:27 pm
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
import java.util.Map;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * An external command a filter asked to run, like the POA Calculator filter's
 * pvlib command.
 *
 * @param command
 *        the command and its arguments
 * @param ran
 *        {@code true} if the command ran, {@code false} if the simulator did
 *        not allow it
 * @param result
 *        the command's JSON result, or {@code null} if it failed
 * @param error
 *        why the command failed or did not run, or {@code null}
 * @param durationMicros
 *        the time the command took, in microseconds
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CommandInfo(List<String> command, boolean ran, @Nullable Map<String, Object> result,
		@Nullable String error, long durationMicros) {

}
