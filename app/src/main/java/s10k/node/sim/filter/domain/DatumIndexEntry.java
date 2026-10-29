/* ==================================================================
 * DatumIndexEntry.java - 29 Oct 2026 8:30:00 pm
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

/**
 * A processed datum in the index of a stored run, to list and find it without
 * its trace.
 *
 * @param seq
 *        the datum's sequence number
 * @param sourceId
 *        the source ID, as the datum entered the Datum Queue
 * @param created
 *        the datum's date
 * @param origin
 *        where the datum came from
 * @param outcome
 *        what became of the datum
 * @param error
 *        {@code true} if processing the datum, or one of its filter steps,
 *        failed
 * @author matt
 * @version 1.0
 */
public record DatumIndexEntry(int seq, @Nullable String sourceId, @Nullable Instant created,
		DatumOrigin origin, DatumOutcome outcome, boolean error) {

	/**
	 * Get the index entry of a datum's trace.
	 *
	 * @param trace
	 *        the trace
	 * @return the entry
	 */
	public static DatumIndexEntry of(DatumTrace trace) {
		final boolean error = trace.error() != null
				|| trace.steps().stream().anyMatch(s -> s.status() == FilterStep.Status.Error);
		return new DatumIndexEntry(trace.seq(), trace.input().sourceId(), trace.input().timestamp(),
				trace.origin(), DatumOutcome.of(trace), error);
	}

}
