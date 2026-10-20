/* ==================================================================
 * SimulationState.java - 18 Oct 2026 8:56:20 am
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
 * A snapshot of the simulated node state.
 *
 * @param opModes
 *        the active operational modes
 * @param localState
 *        the Local State entries
 * @param metrics
 *        the metrics filters saved, oldest first
 * @param localStateCsv
 *        the Local State in the SolarNode Local State CSV format, or
 *        {@code null}
 * @param history
 *        the normal (filtered) datum history, as a map of source IDs to datum,
 *        newest first, or {@code null}
 * @param unfilteredHistory
 *        the unfiltered datum history, as a map of source IDs to datum, newest
 *        first, or {@code null}
 * @param queue
 *        the sequence numbers of datum waiting in the Datum Queue, or
 *        {@code null}
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SimulationState(List<String> opModes, List<LocalStateInfo> localState,
		List<MetricInfo> metrics, @Nullable String localStateCsv,
		@Nullable Map<String, List<DatumInfo>> history,
		@Nullable Map<String, List<DatumInfo>> unfilteredHistory, @Nullable List<Integer> queue) {

}
