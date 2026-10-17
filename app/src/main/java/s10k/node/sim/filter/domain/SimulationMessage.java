/* ==================================================================
 * SimulationMessage.java - 18 Oct 2026 8:56:20 am
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
 * A message about a simulation, such as a configuration problem or a limit that
 * was reached.
 *
 * @param level
 *        the message level
 * @param code
 *        a code that identifies the kind of message, one of the {@code CODE_}
 *        constants
 * @param message
 *        the message
 * @param filterId
 *        the {@code id} of the filter configuration the message is about, or
 *        {@code null}
 * @param datumSeq
 *        the sequence number of the datum the message is about, or {@code null}
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record SimulationMessage(Level level, String code, String message, @Nullable String filterId,
		@Nullable Integer datumSeq) {

	/** A filter type the simulator does not support. */
	public static final String CODE_UNSUPPORTED_FILTER = "UnsupportedFilter";

	/** A setting the filter does not have. */
	public static final String CODE_UNKNOWN_SETTING = "UnknownSetting";

	/** A setting value the filter could not accept. */
	public static final String CODE_INVALID_SETTING = "InvalidSetting";

	/** More than one filter with the same Service Name. */
	public static final String CODE_DUPLICATE_UID = "DuplicateUid";

	/** A chain member without a Service Name, which a chain cannot refer to. */
	public static final String CODE_MISSING_UID = "MissingUid";

	/** A chain member that does not exist. */
	public static final String CODE_UNKNOWN_CHAIN_MEMBER = "UnknownChainMember";

	/** A chain that contains itself. */
	public static final String CODE_CHAIN_CYCLE = "ChainCycle";

	/** A filter feature that the simulator cannot reproduce faithfully. */
	public static final String CODE_FILTER_LIMITATION = "FilterLimitation";

	/** An error starting or stopping a filter. */
	public static final String CODE_FILTER_ERROR = "FilterError";

	/** A generated datum discarded because of the generation depth limit. */
	public static final String CODE_GENERATION_DEPTH_LIMIT = "GenerationDepthLimit";

	/** Processing stopped because of the datum limit. */
	public static final String CODE_DATUM_LIMIT = "DatumLimit";

	/** Processing stopped because of the timeout. */
	public static final String CODE_TIMEOUT = "Timeout";

	/** History datum that do not fit in the datum history. */
	public static final String CODE_HISTORY_OVERFLOW = "HistoryOverflow";

	/** A history datum that is newer than the first input datum. */
	public static final String CODE_HISTORY_AFTER_INPUT = "HistoryAfterInput";

	/**
	 * Message levels.
	 */
	public enum Level {

		/** Information. */
		Info,

		/** A warning. */
		Warning,

	}

	/**
	 * Create a warning message.
	 *
	 * @param code
	 *        the code
	 * @param message
	 *        the message
	 * @param filterId
	 *        the filter configuration {@code id}, or {@code null}
	 * @param datumSeq
	 *        the datum sequence number, or {@code null}
	 * @return the message
	 */
	public static SimulationMessage warning(String code, String message, @Nullable String filterId,
			@Nullable Integer datumSeq) {
		return new SimulationMessage(Level.Warning, code, message, filterId, datumSeq);
	}

}
