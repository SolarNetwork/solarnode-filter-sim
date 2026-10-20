/* ==================================================================
 * FilterStep.java - 18 Oct 2026 8:56:20 am
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
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One filter invocation while processing a datum.
 *
 * <p>
 * The {@code parameters}, {@code opModes}, {@code localState},
 * {@code generated}, {@code instructions}, {@code metrics}, and
 * {@code commands} properties describe what the filter itself did. For a filter
 * chain they are left empty, because its member steps report them.
 * </p>
 *
 * @param id
 *        the step ID, unique within the simulation
 * @param filterId
 *        the {@code id} of the filter configuration
 * @param uid
 *        the filter's Service Name, or {@code null}
 * @param factoryUid
 *        the filter's component factory UID
 * @param depth
 *        the chain nesting depth, starting at {@code 0} for the entry filter
 * @param status
 *        the outcome of the step
 * @param input
 *        the samples passed to the filter
 * @param output
 *        the samples the filter returned, for a {@link Status#Changed} step
 *        only; for other steps the output is the same as the input, or there is
 *        no output
 * @param outputTimestamp
 *        a new datum timestamp returned by the filter, or {@code null} if
 *        unchanged
 * @param parameters
 *        the filter parameters after the step, if the filter changed them
 * @param opModes
 *        the operational modes the filter changed, or {@code null}
 * @param localState
 *        the Local State the filter changed
 * @param generated
 *        the sequence numbers of datum the filter generated
 * @param instructions
 *        the instructions the filter issued
 * @param metrics
 *        the metrics the filter saved
 * @param commands
 *        the external commands the filter ran
 * @param error
 *        the error the filter threw, or {@code null}
 * @param durationMicros
 *        the time the filter took, in microseconds
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record FilterStep(int id, String filterId, @Nullable String uid, String factoryUid, int depth,
		Status status, SamplesInfo input, @Nullable SamplesInfo output,
		@Nullable Instant outputTimestamp, @Nullable Map<String, Object> parameters,
		@Nullable OpModeChange opModes, @Nullable List<LocalStateChange> localState,
		@Nullable List<Integer> generated, @Nullable List<InstructionInfo> instructions,
		@Nullable List<MetricInfo> metrics, @Nullable List<CommandInfo> commands, @Nullable String error,
		long durationMicros) {

	/**
	 * Filter step outcomes.
	 */
	public enum Status {

		/** The filter changed the datum. */
		Changed,

		/** The filter returned the datum unchanged. */
		Unchanged,

		/** The filter discarded the datum. */
		Dropped,

		/**
		 * The filter ignored the datum, such as when its source ID, required
		 * mode, or required tag conditions do not match. Filters also ignore
		 * datum for other reasons, like missing configuration.
		 */
		Skipped,

		/** The filter threw an exception. */
		Error,

		/** The filter type is not supported, so the datum passed through. */
		Unsupported,

	}

}
