/* ==================================================================
 * SimDatumSources.java - 29 Oct 2026 7:30:00 pm
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
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.Trigger;
import s10k.node.sim.filter.domain.ControlInfo;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.ControlUpdate;
import s10k.node.sim.filter.registry.DatumSourceTypeDefinition;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.domain.NodeControlInfo;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.job.SimpleManagedJob;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.service.DatumSourceIdProvider;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.util.StringUtils;

/**
 * The simulation's datum sources.
 *
 * <p>
 * Each datum source is a real SolarNode datum source, wrapped in the node's own
 * {@code DatumDataSourcePollManagedJob} and {@link SimpleManagedJob}, which the
 * simulation polls on the job's schedule.
 * </p>
 *
 * <p>
 * A datum source that is an {@link InstructionHandler}, such as the Mock
 * Battery, handles instructions through {@link #handler(Source)}, which reports
 * each successful set of one of its controls as a {@link ControlUpdate}. A
 * control's values are only those the datum source can give without side
 * effects: the value before the set only for controls whose value only a set
 * changes (the Mock Battery's target power rate, but not its state of charge),
 * and the value after.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public final class SimDatumSources implements SimEventAdmin.ControlListener {

	/** A simulated datum source. */
	public static final class Source {

		private final String id;
		private final String label;
		private final DatumSourceTypeDefinition type;
		private final SimpleManagedJob job;
		private final DatumSourceIdProvider datumSource;
		private final Map<String, Control> controls = new LinkedHashMap<>(2);
		private @Nullable Trigger trigger;
		private int polls;
		private int emptyPolls;
		private @Nullable Instant firstEmptyPoll;
		private int datum;

		private Source(String id, String label, DatumSourceTypeDefinition type, SimpleManagedJob job,
				DatumSourceIdProvider datumSource) {
			super();
			this.id = id;
			this.label = label;
			this.type = type;
			this.job = job;
			this.datumSource = datumSource;
		}

		/**
		 * Get the {@code id} of the datum source configuration.
		 *
		 * @return the ID
		 */
		public String id() {
			return id;
		}

		/**
		 * Get the label of the datum source in messages, such as
		 * {@code The datum source "Meter"}.
		 *
		 * @return the label
		 */
		public String label() {
			return label;
		}

		/**
		 * Get the datum source type.
		 *
		 * @return the type
		 */
		public DatumSourceTypeDefinition type() {
			return type;
		}

		/**
		 * Get the job that polls the datum source.
		 *
		 * @return the job
		 */
		public SimpleManagedJob job() {
			return job;
		}

		/**
		 * Get the datum source.
		 *
		 * @return the datum source, a {@code DatumDataSource}, or a
		 *         {@code MultiDatumDataSource} if the type is a multi datum
		 *         source type
		 */
		public DatumSourceIdProvider datumSource() {
			return datumSource;
		}

		/**
		 * Get the poll trigger.
		 *
		 * @return the trigger, or {@code null} if the datum source is not
		 *         polled
		 */
		public @Nullable Trigger trigger() {
			return trigger;
		}

		/**
		 * Set the poll trigger.
		 *
		 * @param trigger
		 *        the trigger, or {@code null} if the datum source is not polled
		 */
		public void setTrigger(@Nullable Trigger trigger) {
			this.trigger = trigger;
		}

		/**
		 * Record a poll.
		 *
		 * @param date
		 *        the time of the poll
		 * @param count
		 *        the number of datum the poll offered
		 */
		public void polled(Instant date, int count) {
			polls++;
			datum += count;
			if ( count == 0 ) {
				emptyPolls++;
				if ( firstEmptyPoll == null ) {
					firstEmptyPoll = date;
				}
			}
		}

		/**
		 * Get the number of polls.
		 *
		 * @return the count
		 */
		public int polls() {
			return polls;
		}

		/**
		 * Get the number of polls that returned no datum.
		 *
		 * @return the count
		 */
		public int emptyPolls() {
			return emptyPolls;
		}

		/**
		 * Get the time of the first poll that returned no datum.
		 *
		 * @return the time, or {@code null}
		 */
		public @Nullable Instant firstEmptyPoll() {
			return firstEmptyPoll;
		}

		/**
		 * Get the number of datum the polls offered.
		 *
		 * @return the count
		 */
		public int datum() {
			return datum;
		}

	}

	/** A control of a datum source. */
	private static final class Control {

		private final String controlId;
		private final boolean readsPrevious;
		private final @Nullable String initialValue;
		private @Nullable String value;
		private @Nullable Instant modified;
		private int updates;

		private Control(String controlId, boolean readsPrevious, @Nullable String initialValue) {
			super();
			this.controlId = controlId;
			this.readsPrevious = readsPrevious;
			this.initialValue = initialValue;
			this.value = initialValue;
		}

	}

	private final InstantSource clock;
	private final SimEventAdmin eventAdmin;
	private final List<Source> sources = new ArrayList<>(2);
	private @Nullable Consumer<ControlUpdate> listener;
	private @Nullable Source setting;
	private final Map<String, Boolean> settingPublished = new LinkedHashMap<>(2);

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the simulated clock
	 * @param eventAdmin
	 *        the event admin, whose control events publish the datum sources'
	 *        control datum
	 */
	public SimDatumSources(InstantSource clock, SimEventAdmin eventAdmin) {
		super();
		this.clock = clock;
		this.eventAdmin = eventAdmin;
		eventAdmin.addControlListener(this);
	}

	/**
	 * Add a datum source.
	 *
	 * @param id
	 *        the {@code id} of the datum source configuration
	 * @param label
	 *        the label of the datum source in messages
	 * @param type
	 *        the type
	 * @param job
	 *        the job that polls the datum source
	 * @param datumSource
	 *        the datum source
	 * @return the source
	 */
	public Source add(String id, String label, DatumSourceTypeDefinition type, SimpleManagedJob job,
			DatumSourceIdProvider datumSource) {
		final Source result = new Source(id, label, type, job, datumSource);
		sources.add(result);
		return result;
	}

	/**
	 * Get the datum sources.
	 *
	 * @return the sources, in the order they were added
	 */
	public List<Source> sources() {
		return List.copyOf(sources);
	}

	/**
	 * Test if there are no datum sources.
	 *
	 * @return {@code true} if there are none
	 */
	public boolean isEmpty() {
		return sources.isEmpty();
	}

	/**
	 * Add a control of a datum source that is a {@link NodeControlProvider},
	 * whose change events then publish datum.
	 *
	 * @param source
	 *        the source
	 * @param controlId
	 *        the control ID, with placeholders resolved
	 * @param readsPrevious
	 *        {@code true} if the control's value can be read before a set
	 *        without side effects, so a set reports the value before it
	 * @param publish
	 *        when the control publishes a datum
	 * @param persist
	 *        {@code true} to persist the datum the control publishes
	 */
	public void addControl(Source source, String controlId, boolean readsPrevious,
			ControlPublishMode publish, boolean persist) {
		source.controls.put(controlId,
				new Control(controlId, readsPrevious, currentValue(source, controlId)));
		eventAdmin.addControl(controlId, publish, persist);
	}

	/**
	 * Test if a datum source has a control.
	 *
	 * @param controlId
	 *        the control ID
	 * @return {@code true} if a datum source has a control with the ID
	 */
	public boolean hasControl(String controlId) {
		for ( Source s : sources ) {
			if ( s.controls.containsKey(controlId) ) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Get the state of every datum source control.
	 *
	 * @param read
	 *        {@code true} to read each control's current value, which has no
	 *        side effects, else the value of its last set (or its starting
	 *        value)
	 * @return the controls, in the order their datum sources were added
	 */
	public List<ControlInfo> controlInfos(boolean read) {
		final List<ControlInfo> result = new ArrayList<>(2);
		for ( Source s : sources ) {
			for ( Control c : s.controls.values() ) {
				final NodeControlInfo info = controlInfo(s, c.controlId);
				final String type = (info != null && info.getType() != null ? info.getType().name()
						: "");
				result.add(new ControlInfo(c.controlId, type,
						read && info != null ? info.getValue() : c.value, c.initialValue, c.modified,
						c.updates, null, null, s.id));
			}
		}
		return result;
	}

	/**
	 * Get an instruction handler for a datum source that is one.
	 *
	 * @param source
	 *        the source, whose datum source is an {@link InstructionHandler}
	 * @return the handler, which reports the source's control sets
	 */
	public InstructionHandler handler(Source source) {
		final InstructionHandler delegate = (InstructionHandler) source.datumSource;
		return new InstructionHandler() {

			@Override
			public boolean handlesTopic(@Nullable String topic) {
				return delegate.handlesTopic(topic);
			}

			@Override
			public @Nullable InstructionStatus processInstruction(Instruction instruction) {
				return process(source, delegate, instruction);
			}

		};
	}

	private @Nullable InstructionStatus process(Source source, InstructionHandler delegate,
			Instruction instruction) {
		// the controls the instruction sets, with their values before
		final Map<Control, @Nullable String> before = new LinkedHashMap<>(2);
		for ( String name : instruction.getParameterNames() ) {
			final Control c = source.controls.get(name);
			if ( c != null ) {
				before.put(c, c.readsPrevious ? currentValue(source, name) : null);
			}
		}
		setting = source;
		settingPublished.clear();
		final InstructionStatus status;
		try {
			status = delegate.processInstruction(instruction);
		} finally {
			setting = null;
		}
		if ( status == null || status.getInstructionState() != InstructionState.Completed ) {
			return status;
		}
		final Instant now = clock.instant();
		final Consumer<ControlUpdate> l = this.listener;
		for ( Map.Entry<Control, @Nullable String> e : before.entrySet() ) {
			final Control c = e.getKey();
			final NodeControlInfo info = controlInfo(source, c.controlId);
			final String value = (info != null && info.getValue() != null ? info.getValue() : "");
			c.value = value;
			c.modified = now;
			c.updates++;
			if ( l != null ) {
				final String asked = instruction.getParameterValue(c.controlId);
				l.accept(new ControlUpdate(c.controlId,
						info != null && info.getType() != null ? info.getType().name() : "",
						e.getValue(), value, settingPublished.getOrDefault(c.controlId, false), null,
						source.id, sameNumber(asked, value) ? null : asked));
			}
		}
		return status;
	}

	/**
	 * Test if a requested value is the same number as a control's value.
	 */
	private static boolean sameNumber(@Nullable String requested, String value) {
		if ( requested == null || requested.equals(value) ) {
			return true;
		}
		final Number a = StringUtils.numberValue(requested);
		final Number b = StringUtils.numberValue(value);
		if ( a == null || b == null ) {
			return false;
		}
		return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
	}

	/**
	 * Get the ID of the datum source that handles an instruction.
	 *
	 * @param source
	 *        the source
	 * @return a function that gives the source's ID
	 */
	public static Function<Instruction, @Nullable String> handlerName(Source source) {
		return _ -> source.id;
	}

	private static @Nullable NodeControlInfo controlInfo(Source source, String controlId) {
		if ( source.datumSource instanceof NodeControlProvider p ) {
			return p.getCurrentControlInfo(controlId);
		}
		return null;
	}

	private static @Nullable String currentValue(Source source, String controlId) {
		final NodeControlInfo info = controlInfo(source, controlId);
		return (info != null ? info.getValue() : null);
	}

	@Override
	public void controlChanged(String controlId, NodeDatum datum, boolean published) {
		final Source s = setting;
		if ( s != null && s.controls.containsKey(controlId) ) {
			settingPublished.merge(controlId, published, Boolean::logicalOr);
		}
	}

	/**
	 * Set the listener for control sets.
	 *
	 * @param listener
	 *        the listener to set, or {@code null}
	 */
	public void setListener(@Nullable Consumer<ControlUpdate> listener) {
		this.listener = listener;
	}

}
