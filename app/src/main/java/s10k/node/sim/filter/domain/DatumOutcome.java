/* ==================================================================
 * DatumOutcome.java - 29 Oct 2026 8:30:00 pm
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
 * What became of a processed datum.
 *
 * @author matt
 * @version 1.0
 */
public enum DatumOutcome {

	/** The filters output the datum, to be persisted. */
	Output,

	/** The filters output the datum, which is not persisted. */
	OutputNotPersisted,

	/** A filter discarded the datum. */
	Discarded,

	/** Processing the datum failed. */
	Error;

	/**
	 * Get the outcome of a datum's trace.
	 *
	 * @param trace
	 *        the trace
	 * @return the outcome
	 */
	public static DatumOutcome of(DatumTrace trace) {
		if ( trace.error() != null ) {
			return Error;
		}
		if ( trace.output() != null ) {
			return (trace.persist() ? Output : OutputNotPersisted);
		}
		return Discarded;
	}

}
