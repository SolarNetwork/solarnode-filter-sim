/* ==================================================================
 * SimNodeControlService.java - 25 Oct 2026 10:12:40 am
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

package s10k.node.sim.filter.sim;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.ControlInfo;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.ControlUpdate;
import net.solarnetwork.domain.BasicNodeControlInfo;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.domain.NodeControlInfo;
import net.solarnetwork.domain.NodeControlPropertyType;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.node.domain.datum.SimpleNodeControlInfoDatum;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.reactor.InstructionUtils;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.util.StringUtils;

/**
 * Simulated SolarNode controls, which {@code SetControlParameter} instructions
 * set the value of.
 *
 * <p>
 * Each control acts like its own SolarNode Mock control
 * ({@code MockNodeControlProvider}): an instruction parameter named after the
 * control ID gives the new value, which is normalized for the control type, or
 * declined if it is not valid for it. Controls are asked in the order they were
 * added, and the first one an instruction names decides its status, like the
 * node's instruction execution service asking each handler in turn.
 * </p>
 *
 * <p>
 * A control publishes a datum of its value, like a Control Datum Source does
 * for each "control changed" event, as its {@link ControlPublishMode} says.
 * Values are dated by the simulated clock.
 * </p>
 *
 * <p>
 * A control's datum has its value as SolarNode's
 * {@code SimpleNodeControlInfoDatum} puts it: a {@code val} status property
 * (and a numeric value as the {@code v} instantaneous property too), or a
 * status property with the control's property name, like a Modbus control with
 * a property name. A property type other than status moves a numeric value to
 * that type, like the GPIO control does.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class SimNodeControlService implements NodeControlProvider, InstructionHandler {

	/**
	 * API for publishing a control's datum to the Datum Queue.
	 */
	@FunctionalInterface
	public interface Publisher {

		/**
		 * Publish a control's datum.
		 *
		 * @param controlId
		 *        the control ID
		 * @param datum
		 *        the datum
		 * @param persist
		 *        {@code true} to persist the datum after filtering
		 */
		void publish(String controlId, NodeDatum datum, boolean persist);

	}

	private static final class Control {

		private final String controlId;
		private final NodeControlPropertyType type;
		private final ControlPublishMode publish;
		private final boolean persist;
		private final @Nullable String initialValue;
		private final @Nullable String propertyName;
		private final @Nullable DatumSamplesType propertyType;
		private @Nullable SimpleNodeControlInfoDatum datum;
		private @Nullable Instant modified;
		private int updates;

		private Control(String controlId, NodeControlPropertyType type, ControlPublishMode publish,
				boolean persist, @Nullable String initialValue, @Nullable String propertyName,
				@Nullable DatumSamplesType propertyType) {
			super();
			this.controlId = controlId;
			this.type = type;
			this.publish = publish;
			this.persist = persist;
			this.initialValue = initialValue;
			this.propertyName = propertyName;
			this.propertyType = propertyType;
		}

		private @Nullable String value() {
			final SimpleNodeControlInfoDatum d = this.datum;
			return (d != null ? d.getValue() : null);
		}

	}

	private final Clock clock;
	private final Map<String, Control> controls = new LinkedHashMap<>(4);
	private @Nullable Consumer<ControlUpdate> listener;
	private @Nullable Publisher publisher;

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the simulated clock, which dates control values
	 */
	public SimNodeControlService(Clock clock) {
		super();
		this.clock = clock;
	}

	/**
	 * Add a control.
	 *
	 * <p>
	 * The starting value is the control's state, not a change, so it is not
	 * published.
	 * </p>
	 *
	 * @param controlId
	 *        the control ID, which must not be used by another control
	 * @param type
	 *        the control type
	 * @param value
	 *        the starting value, or {@code null} for none
	 * @param publish
	 *        when the control publishes a datum
	 * @param persist
	 *        {@code true} to persist the datum the control publishes
	 * @param propertyName
	 *        the datum property name of the value, or {@code null} or empty for
	 *        SolarNode's default
	 * @param propertyType
	 *        the datum property type of the value, or {@code null} for
	 *        {@code Status}
	 * @return {@code true} if the control has the starting value, or
	 *         {@code false} if the value is not valid for the type, so the
	 *         control has no value
	 * @throws IllegalArgumentException
	 *         if the control ID is already used
	 */
	public boolean addControl(String controlId, NodeControlPropertyType type, @Nullable String value,
			ControlPublishMode publish, boolean persist, @Nullable String propertyName,
			@Nullable DatumSamplesType propertyType) {
		if ( controls.containsKey(controlId) ) {
			throw new IllegalArgumentException("Control ID [%s] already used.".formatted(controlId));
		}
		final String initial = (value != null && !value.isEmpty() ? normalizedValue(type, value) : null);
		final Control control = new Control(controlId, type, publish, persist, initial,
				propertyName != null && !propertyName.isBlank() ? propertyName.trim() : null,
				propertyType);
		if ( initial != null ) {
			control.datum = controlDatum(control, initial);
		}
		controls.put(controlId, control);
		return (initial != null || value == null || value.isEmpty());
	}

	/**
	 * Test if a control exists.
	 *
	 * @param controlId
	 *        the control ID
	 * @return {@code true} if the control exists
	 */
	public boolean hasControl(String controlId) {
		return controls.containsKey(controlId);
	}

	/**
	 * Get the state of every control.
	 *
	 * @return the controls, in the order they were added
	 */
	public List<ControlInfo> controlInfos() {
		final List<ControlInfo> result = new ArrayList<>(controls.size());
		for ( Control c : controls.values() ) {
			result.add(new ControlInfo(c.controlId, c.type.name(), c.value(), c.initialValue, c.modified,
					c.updates));
		}
		return result;
	}

	@Override
	public @Nullable String getUid() {
		return null;
	}

	@Override
	public @Nullable String getGroupUid() {
		return null;
	}

	@Override
	public String getDisplayName() {
		return "Simulated Controls";
	}

	@Override
	public List<String> getAvailableControlIds() {
		return new ArrayList<>(controls.keySet());
	}

	@Override
	public @Nullable NodeControlInfo getCurrentControlInfo(String controlId) {
		final Control control = controls.get(controlId);
		return (control != null ? control.datum : null);
	}

	@Override
	public boolean handlesTopic(@Nullable String topic) {
		return InstructionHandler.TOPIC_SET_CONTROL_PARAMETER.equals(topic);
	}

	@Override
	public @Nullable InstructionStatus processInstruction(Instruction instruction) {
		if ( !handlesTopic(instruction.getTopic()) ) {
			return null;
		}
		// like asking one Mock control after another
		for ( Control control : controls.values() ) {
			if ( instruction.isParameterAvailable(control.controlId) ) {
				final boolean ok = set(control, instruction.getParameterValue(control.controlId));
				return InstructionUtils.createStatus(instruction,
						ok ? InstructionState.Completed : InstructionState.Declined);
			}
		}
		return null;
	}

	private boolean set(Control control, @Nullable String value) {
		final String newValue = (value != null && !value.isEmpty() ? normalizedValue(control.type, value)
				: null);
		if ( newValue == null ) {
			return false;
		}
		final String previous = control.value();
		final boolean changed = !newValue.equalsIgnoreCase(previous);
		final SimpleNodeControlInfoDatum datum = controlDatum(control, newValue);
		control.datum = datum;
		control.modified = datum.getTimestamp();
		control.updates++;
		final Publisher p = this.publisher;
		final boolean publish = p != null && (control.publish == ControlPublishMode.Update
				|| (control.publish == ControlPublishMode.Change && changed));
		final Consumer<ControlUpdate> l = this.listener;
		if ( l != null ) {
			l.accept(new ControlUpdate(control.controlId, control.type.name(), previous, newValue,
					publish));
		}
		if ( publish && p != null ) {
			p.publish(control.controlId, datum, control.persist);
		}
		return true;
	}

	private SimpleNodeControlInfoDatum controlDatum(Control control, String value) {
		// @formatter:off
		final NodeControlInfo info = BasicNodeControlInfo.builder()
				.withControlId(control.controlId)
				.withPropertyName(control.propertyName)
				.withType(control.type)
				.withReadonly(false)
				.withValue(value)
				.build();
		// @formatter:on
		final SimpleNodeControlInfoDatum d = new SimpleNodeControlInfoDatum(info, clock.instant());
		final DatumSamplesType propType = control.propertyType;
		if ( propType != null && propType != DatumSamplesType.Status ) {
			// like the GPIO control, move the property from Status to the type, as a number
			final String name = (control.propertyName != null ? control.propertyName
					: SimpleNodeControlInfoDatum.DEFAULT_PROPERTY_NAME);
			final Object v = d.getSampleValue(DatumSamplesType.Status, name);
			final Number n = (v instanceof Number num ? num
					: v != null ? StringUtils.numberValue(v.toString()) : null);
			if ( n != null ) {
				d.putSampleValue(propType, name, n);
				d.putSampleValue(DatumSamplesType.Status, name, null);
				final Map<String, ?> status = d.getSampleData(DatumSamplesType.Status);
				if ( status != null && status.isEmpty() ) {
					d.setSampleData(DatumSamplesType.Status, null);
				}
			}
		}
		return d;
	}

	/**
	 * Normalize a control value for a control type, like SolarNode's Mock
	 * control.
	 *
	 * @param type
	 *        the control type
	 * @param value
	 *        the value
	 * @return the normalized value, or {@code null} if the value is not valid
	 *         for the type
	 */
	public static @Nullable String normalizedValue(NodeControlPropertyType type, String value) {
		return switch (type) {
			case Boolean -> String.valueOf(StringUtils.parseBoolean(value));
			case Float, Percent -> {
				try {
					yield new BigDecimal(value).toPlainString();
				} catch ( NumberFormatException e ) {
					yield null;
				}
			}
			case Integer -> {
				try {
					yield String.valueOf(Integer.parseInt(value));
				} catch ( NumberFormatException e ) {
					yield null;
				}
			}
			default -> value;
		};
	}

	/**
	 * Set the listener for control value sets.
	 *
	 * @param listener
	 *        the listener to set, or {@code null}
	 */
	public void setListener(@Nullable Consumer<ControlUpdate> listener) {
		this.listener = listener;
	}

	/**
	 * Set the publisher of control datum.
	 *
	 * @param publisher
	 *        the publisher to set, or {@code null} to publish nothing
	 */
	public void setPublisher(@Nullable Publisher publisher) {
		this.publisher = publisher;
	}

}
