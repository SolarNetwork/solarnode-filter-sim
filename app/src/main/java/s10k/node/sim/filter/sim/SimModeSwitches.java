/* ==================================================================
 * SimModeSwitches.java - 29 Oct 2026 4:35:00 pm
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

import java.time.InstantSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.ControlInfo;
import s10k.node.sim.filter.domain.ControlKind;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.ControlUpdate;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.domain.NodeControlInfo;
import net.solarnetwork.domain.NodeControlPropertyType;
import net.solarnetwork.node.control.opmode.OperationalModeSwitch;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.service.OperationalModesService;
import net.solarnetwork.service.StaticOptionalService;

/**
 * The simulation's Operational Mode Switches.
 *
 * <p>
 * Each switch is SolarNode's own {@link OperationalModeSwitch}: a
 * {@code Boolean} control whose value is whether its operational mode is
 * active. A {@code SetControlParameter} instruction with a parameter named its
 * control ID enables or disables the mode. The switch hears of mode changes
 * from the operational modes service's events, whatever their cause, and posts
 * a control event of its new value, which {@link SimEventAdmin} publishes as
 * the switch's publish mode says.
 * </p>
 *
 * <p>
 * This class asks each switch in turn to handle an instruction, like the node's
 * instruction execution service asking each switch, and reports each successful
 * set, and each value change the switch posts outside of a set, as a
 * {@link ControlUpdate}.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class SimModeSwitches implements InstructionHandler, SimEventAdmin.ControlListener {

	private static final String TYPE = NodeControlPropertyType.Boolean.name();

	private static final class Switch {

		private final OperationalModeSwitch control;
		private final String controlId;
		private final @Nullable String mode;
		private @Nullable String initialValue;
		private @Nullable Instant modified;
		private int updates;

		private Switch(OperationalModeSwitch control, String controlId, @Nullable String mode) {
			super();
			this.control = control;
			this.controlId = controlId;
			this.mode = mode;
		}

		private String value() {
			return String.valueOf(control.isModeActive());
		}

	}

	private final InstantSource clock;
	private final OperationalModesService opModesService;
	private final SimEventAdmin eventAdmin;
	private final Map<String, Switch> switches = new LinkedHashMap<>(2);
	private @Nullable Consumer<ControlUpdate> listener;
	private @Nullable Switch setting;
	private boolean settingPublished;

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the simulated clock, which dates the switches' datum
	 * @param opModesService
	 *        the operational modes service
	 * @param eventAdmin
	 *        the event admin
	 */
	public SimModeSwitches(InstantSource clock, OperationalModesService opModesService,
			SimEventAdmin eventAdmin) {
		super();
		this.clock = clock;
		this.opModesService = opModesService;
		this.eventAdmin = eventAdmin;
		eventAdmin.addControlListener(this);
	}

	/**
	 * Add a switch.
	 *
	 * @param controlId
	 *        the control ID, which must not be used by another switch
	 * @param mode
	 *        the operational mode, or {@code null} for none, so the switch
	 *        handles no instructions
	 * @param publish
	 *        when the switch publishes a datum: any mode but {@code Never}
	 *        publishes each change
	 * @param persist
	 *        {@code true} to persist the datum the switch publishes
	 * @return the switch, to register as a service
	 * @throws IllegalArgumentException
	 *         if the control ID is already used
	 */
	public OperationalModeSwitch add(String controlId, @Nullable String mode, ControlPublishMode publish,
			boolean persist) {
		if ( switches.containsKey(controlId) ) {
			throw new IllegalArgumentException("Control ID [%s] already used.".formatted(controlId));
		}
		final OperationalModeSwitch control = new OperationalModeSwitch(clock,
				new StaticOptionalService<>(opModesService));
		control.setControlId(controlId);
		control.setMode(mode);
		control.setEventAdmin(new StaticOptionalService<>(eventAdmin));
		eventAdmin.addModeHandler(control);
		eventAdmin.addControl(controlId, publish, persist);
		switches.put(controlId, new Switch(control, controlId, mode));
		return control;
	}

	/**
	 * Test if there are no switches.
	 *
	 * @return {@code true} if there are none
	 */
	public boolean isEmpty() {
		return switches.isEmpty();
	}

	/**
	 * Test if a switch exists.
	 *
	 * @param controlId
	 *        the control ID
	 * @return {@code true} if a switch has the control ID
	 */
	public boolean hasControl(String controlId) {
		return switches.containsKey(controlId);
	}

	/**
	 * Record the switches' starting values, once the operational modes service
	 * has started.
	 */
	public void started() {
		for ( Switch s : switches.values() ) {
			s.initialValue = s.value();
		}
	}

	/**
	 * Get the state of every switch.
	 *
	 * @return the switches, in the order they were added
	 */
	public List<ControlInfo> controlInfos() {
		final List<ControlInfo> result = new ArrayList<>(switches.size());
		for ( Switch s : switches.values() ) {
			result.add(new ControlInfo(s.controlId, TYPE, s.value(), s.initialValue, s.modified,
					s.updates, ControlKind.OperationalModeSwitch, s.mode));
		}
		return result;
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
		for ( Switch s : switches.values() ) {
			if ( !handles(s, instruction) ) {
				continue;
			}
			final String before = s.value();
			setting = s;
			settingPublished = false;
			final InstructionStatus status;
			try {
				status = s.control.processInstruction(instruction);
			} finally {
				setting = null;
			}
			if ( status == null ) {
				continue;
			}
			if ( status.getInstructionState() == InstructionState.Completed ) {
				s.modified = clock.instant();
				s.updates++;
				final Consumer<ControlUpdate> l = this.listener;
				if ( l != null ) {
					l.accept(new ControlUpdate(s.controlId, TYPE, before, s.value(), settingPublished));
				}
			}
			return status;
		}
		return null;
	}

	/**
	 * Get the ID of the switch that handles an instruction.
	 *
	 * @param instruction
	 *        the instruction
	 * @return the control ID, or {@code null} if no switch handles it
	 */
	public @Nullable String handlingControlId(Instruction instruction) {
		if ( !handlesTopic(instruction.getTopic()) ) {
			return null;
		}
		for ( Switch s : switches.values() ) {
			if ( handles(s, instruction) ) {
				return s.controlId;
			}
		}
		return null;
	}

	/**
	 * Test if a switch handles an instruction, like its own
	 * {@code processInstruction()}: it needs a mode and a parameter named its
	 * control ID.
	 */
	private static boolean handles(Switch s, Instruction instruction) {
		return (s.mode != null && instruction.getParameterValue(s.controlId) != null);
	}

	@Override
	public void controlChanged(String controlId, NodeDatum datum, boolean published) {
		final Switch s = switches.get(controlId);
		if ( s == null ) {
			return;
		}
		if ( s == setting ) {
			// reported with the set
			settingPublished |= published;
			return;
		}
		// the mode changed some other way, such as the Operational Mode filter
		final Consumer<ControlUpdate> l = this.listener;
		if ( l != null && datum instanceof NodeControlInfo info && info.getValue() != null ) {
			final boolean value = Boolean.parseBoolean(info.getValue());
			l.accept(new ControlUpdate(controlId, TYPE, String.valueOf(!value), String.valueOf(value),
					published, s.mode));
		}
	}

	/**
	 * Set the listener for switch value sets and changes.
	 *
	 * @param listener
	 *        the listener to set, or {@code null}
	 */
	public void setListener(@Nullable Consumer<ControlUpdate> listener) {
		this.listener = listener;
	}

}
