/* ==================================================================
 * ControlUpdate.java - 25 Oct 2026 10:12:40 am
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
 * A successful set of a simulated control's value, which might not change the
 * value, or a change of an Operational Mode Switch's value without a set.
 *
 * <p>
 * A datum source's control has only the values the datum source can give
 * without side effects: a Mock Battery's target power rate before and after,
 * and the state of charge it was set to, without the one before.
 * </p>
 *
 * @param controlId
 *        the control ID
 * @param type
 *        the control type name
 * @param previousValue
 *        the value before the set, or {@code null} if the control had none
 * @param value
 *        the value the set gave the control, normalized for its type
 * @param published
 *        {@code true} if the control published a datum of the value
 * @param mode
 *        for an Operational Mode Switch whose value changed without a set,
 *        because its operational mode was enabled, disabled, or expired some
 *        other way, the mode; else {@code null}
 * @param component
 *        for a datum source's control, the {@code id} of the datum source
 *        configuration; else {@code null}
 * @param requested
 *        the value the instruction asked for, when the control set another
 *        value, such as a power rate over the limit; else {@code null}
 * @author matt
 * @version 1.2
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ControlUpdate(String controlId, String type, @Nullable String previousValue, String value,
		boolean published, @Nullable String mode, @Nullable String component,
		@Nullable String requested) {

	/**
	 * Constructor, for a set.
	 *
	 * @param controlId
	 *        the control ID
	 * @param type
	 *        the control type name
	 * @param previousValue
	 *        the value before the set
	 * @param value
	 *        the value the set gave the control
	 * @param published
	 *        {@code true} if the control published a datum of the value
	 */
	public ControlUpdate(String controlId, String type, @Nullable String previousValue, String value,
			boolean published) {
		this(controlId, type, previousValue, value, published, null, null, null);
	}

	/**
	 * Constructor, for an Operational Mode Switch.
	 *
	 * @param controlId
	 *        the control ID
	 * @param type
	 *        the control type name
	 * @param previousValue
	 *        the value before
	 * @param value
	 *        the value after
	 * @param published
	 *        {@code true} if the control published a datum of the value
	 * @param mode
	 *        the mode, for a change without a set
	 */
	public ControlUpdate(String controlId, String type, @Nullable String previousValue, String value,
			boolean published, @Nullable String mode) {
		this(controlId, type, previousValue, value, published, mode, null, null);
	}

}
