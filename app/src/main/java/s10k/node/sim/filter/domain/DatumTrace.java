/* ==================================================================
 * DatumTrace.java - 18 Oct 2026 8:56:20 am
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
 * The trace of one datum through the Datum Queue.
 *
 * @param seq
 *        the processing sequence number, starting at {@code 1}
 * @param origin
 *        where the datum came from
 * @param input
 *        the datum, as offered to the Datum Queue
 * @param persist
 *        {@code true} if the datum is persisted (uploaded) after filtering, or
 *        {@code false} if it is only passed to Datum Queue consumers
 * @param steps
 *        the filter invocations, in order; nested chain members follow their
 *        chain, with a greater depth
 * @param output
 *        the datum after all filters were applied, or {@code null} if the
 *        filters discarded it
 * @param error
 *        an error that stopped the filters, or {@code null}
 * @param startState
 *        the state of the simulated node when this datum started processing,
 *        for {@link SimulationOptions.Detail#Debug} results only
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record DatumTrace(int seq, DatumOrigin origin, DatumInfo input, boolean persist,
		List<FilterStep> steps, @Nullable DatumInfo output, @Nullable String error,
		@Nullable SimulationState startState) {

}
