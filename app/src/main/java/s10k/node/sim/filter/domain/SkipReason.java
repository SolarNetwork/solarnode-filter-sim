/* ==================================================================
 * SkipReason.java - 21 Oct 2026 9:12:40 am
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

import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Why a filter ignored a datum: a condition of the filter that the datum did
 * not meet.
 *
 * @param condition
 *        the condition
 * @param expected
 *        what the condition requires: the Source ID pattern, the Required Mode,
 *        or the Required Tag expression; {@code null} for
 *        {@link Condition#Other}
 * @param actual
 *        what the datum or node had instead: the datum's source ID, the active
 *        operational modes, or the datum's tags (comma-delimited, empty for
 *        none), or {@code null} if unknown
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkipReason(Condition condition, @Nullable String expected, @Nullable String actual) {

	/**
	 * The conditions a filter checks before it filters a datum.
	 */
	public enum Condition {

		/** The datum's source ID does not match the Source ID pattern. */
		SourceId,

		/** The Required Mode is not active. */
		RequiredMode,

		/** The datum does not have the Required Tag. */
		RequiredTag,

		/**
		 * A reason of the filter's own, which the simulator cannot tell: every
		 * standard condition was met, but a filter can ignore a datum for any
		 * reason, such as a setting it requires that is not configured.
		 */
		Other,

	}

}
