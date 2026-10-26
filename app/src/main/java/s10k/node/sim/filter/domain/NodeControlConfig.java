/* ==================================================================
 * NodeControlConfig.java - 25 Oct 2026 10:12:40 am
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

/**
 * A simulated SolarNode control, which {@code SetControlParameter}
 * instructions, like those of the Control Updater filter, set the value of.
 *
 * @param controlId
 *        the control ID
 * @param type
 *        the control type, as a {@code NodeControlPropertyType} name or key, or
 *        {@code null} for {@code Boolean}, the Mock control's default
 * @param value
 *        the starting value, or {@code null} for none
 * @param publish
 *        when the control publishes a datum of its value, or {@code null} for
 *        {@link ControlPublishMode#Update}
 * @param persist
 *        {@code true} to persist the datum the control publishes, like the
 *        Control Datum Source's {@code PollAndEvent} persist mode, or
 *        {@code null} for {@code false}, like its default {@code Poll} mode
 * @param propertyName
 *        the datum property name of the value the control publishes, like the
 *        Modbus control's {@code controlPropertyName} or the GPIO control's
 *        {@code propertyKey}, or {@code null} for SolarNode's default
 *        {@code val} (with the number also as {@code v})
 * @param propertyType
 *        the datum property type of the value, as a {@code DatumSamplesType}
 *        name or key ({@code Instantaneous}, {@code Accumulating}, or
 *        {@code Status}), like the GPIO control's Property Type setting, or
 *        {@code null} for {@code Status}; a value that is not a number stays a
 *        {@code Status} property
 * @author matt
 * @version 1.0
 */
public record NodeControlConfig(@Nullable String controlId, @Nullable String type,
		@Nullable String value, @Nullable ControlPublishMode publish, @Nullable Boolean persist,
		@Nullable String propertyName, @Nullable String propertyType) {

	/**
	 * Constructor, with the default datum property.
	 *
	 * @param controlId
	 *        the control ID
	 * @param type
	 *        the control type
	 * @param value
	 *        the starting value
	 * @param publish
	 *        when the control publishes a datum
	 * @param persist
	 *        {@code true} to persist the datum the control publishes
	 */
	public NodeControlConfig(@Nullable String controlId, @Nullable String type, @Nullable String value,
			@Nullable ControlPublishMode publish, @Nullable Boolean persist) {
		this(controlId, type, value, publish, persist, null, null);
	}

	/** The default {@code publish} value. */
	public static final ControlPublishMode DEFAULT_PUBLISH = ControlPublishMode.Update;

	/**
	 * Get when the control publishes a datum.
	 *
	 * @return the publish mode, never {@code null}
	 */
	public ControlPublishMode publishValue() {
		return (publish != null ? publish : DEFAULT_PUBLISH);
	}

	/**
	 * Test if the datum the control publishes are persisted.
	 *
	 * @return {@code true} to persist them
	 */
	public boolean persistValue() {
		return (persist != null && persist);
	}

}
