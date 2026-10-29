/* ==================================================================
 * SimInstructionService.java - 18 Oct 2026 8:56:20 am
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

import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.InstructionStatus.InstructionState;
import net.solarnetwork.node.control.conductor.ControlConductor;
import net.solarnetwork.node.reactor.Instruction;
import net.solarnetwork.node.reactor.InstructionExecutionService;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.reactor.InstructionStatus;
import net.solarnetwork.node.reactor.InstructionUtils;
import net.solarnetwork.node.reactor.ReactorService;
import net.solarnetwork.util.StringUtils;
import s10k.node.sim.filter.domain.InstructionHandlerInfo;
import s10k.node.sim.filter.domain.InstructionInfo;

/**
 * Simulated instruction execution and reactor services.
 *
 * <p>
 * Executed instructions go to the handlers in order, like SolarNode's
 * {@code SimpleInstructionExecutionService} asks its handlers in service order:
 * the operational modes service, the metadata services, the simulated controls,
 * and then the Control Conductors ({@link InstructionHandlerInfo.Type}). The
 * first status that is not {@code null} wins, so an instruction no handler
 * handles has no status. Topics no handler can handle, other than
 * {@code SetControlParameter}, {@code OrchestrateControls}, and {@code Signal},
 * complete successfully.
 * </p>
 *
 * <p>
 * Stored instructions are kept like SolarNode's {@code SimpleReactorService}
 * keeps them, with their status and the date they are due. The simulation runs
 * those still {@code Received} when they are due, like the Instruction
 * Execution Job. An instruction that was executed but not handled, or that
 * threw an error, is not tried again, as nothing in a run changes the handlers.
 * </p>
 *
 * <p>
 * Like {@code SimpleReactorService}, it is also the handler of
 * {@code CancelInstruction} instructions, ahead of the others: a stored
 * {@code Received} instruction with the {@code id} parameter's ID (and the same
 * instructor ID) changes to {@code Declined}, and so do the {@code Received}
 * instructions it is the parent of, such as a Control Conductor's tasks. With
 * {@code force}, the children are cancelled even when the instruction itself
 * cannot be, for example an {@code OrchestrateControls} instruction that has
 * completed. A deferred {@code CancelInstruction} runs at once, as on a node.
 * </p>
 *
 * <p>
 * Each instruction gets a number, from {@code 1}, and the listener gets its
 * {@link InstructionInfo} every time it is stored or executed, with the handler
 * that gave it its status.
 * </p>
 *
 * @author matt
 * @version 2.1
 */
public class SimInstructionService
		implements InstructionExecutionService, ReactorService, InstructionHandler {

	/** The topics only handlers complete. */
	private static final Set<String> HANDLED_TOPICS = Set.of(
			InstructionHandler.TOPIC_SET_CONTROL_PARAMETER, ControlConductor.TOPIC_ORCHESTRATE_CONTROLS,
			InstructionHandler.TOPIC_SIGNAL);

	/**
	 * The filter step that stored an instruction.
	 *
	 * @param datumSeq
	 *        the sequence number of the datum being processed, or {@code null}
	 * @param stepId
	 *        the step ID, or {@code null}
	 * @param filterId
	 *        the filter configuration {@code id}, or {@code null}
	 */
	public record Issuer(@Nullable Integer datumSeq, @Nullable Integer stepId,
			@Nullable String filterId) {

	}

	/**
	 * A stored instruction.
	 */
	public static final class StoredInstruction {

		private final int number;
		private final Instruction instruction;
		private final Instant due;
		private final @Nullable Issuer issuer;
		private @Nullable InstructionState state;
		private boolean tried;

		private StoredInstruction(int number, Instruction instruction, Instant due,
				@Nullable Issuer issuer, @Nullable InstructionState state) {
			super();
			this.number = number;
			this.instruction = instruction;
			this.due = due;
			this.issuer = issuer;
			this.state = state;
		}

		/**
		 * Get the instruction number.
		 *
		 * @return the number
		 */
		public int number() {
			return number;
		}

		/**
		 * Get the instruction.
		 *
		 * @return the instruction
		 */
		public Instruction instruction() {
			return instruction;
		}

		/**
		 * Get the date the instruction is due.
		 *
		 * @return the date
		 */
		public Instant due() {
			return due;
		}

		/**
		 * Get the filter step that stored the instruction, or the one that
		 * stored the instruction running when it was stored.
		 *
		 * @return the issuer, or {@code null}
		 */
		public @Nullable Issuer issuer() {
			return issuer;
		}

		/**
		 * Test if the instruction waits for the Instruction Execution Job.
		 *
		 * @return {@code true} if it is {@code Received} and not tried yet
		 */
		public boolean waiting() {
			return state == InstructionState.Received && !tried;
		}

	}

	/**
	 * An instruction handler.
	 *
	 * @param type
	 *        the kind of handler, which sets its place in the order
	 * @param handler
	 *        the handler
	 * @param name
	 *        gives the name of what in the handler handled an instruction
	 */
	private record Handler(InstructionHandlerInfo.Type type, InstructionHandler handler,
			Function<Instruction, @Nullable String> name) {

	}

	private final InstantSource clock;
	private final List<Handler> handlers = new ArrayList<>(8);
	private final List<ControlConductor> conductors = new ArrayList<>(2);
	private final Map<Integer, InstructionHandlerInfo> handledBy = new HashMap<>(16);
	private final Map<String, Integer> numbers = new HashMap<>(16);
	private final Map<Integer, @Nullable Integer> parents = new HashMap<>(16);
	private final Map<Integer, StoredInstruction> stored = new LinkedHashMap<>(16);
	private final Deque<Integer> running = new ArrayDeque<>(4);
	private int nextNumber = 1;
	private @Nullable Consumer<InstructionInfo> listener;
	private @Nullable Supplier<@Nullable Issuer> issuer;

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the clock, for the date stored instructions without an execution
	 *        date are due
	 */
	public SimInstructionService(InstantSource clock) {
		super();
		this.clock = clock;
	}

	/**
	 * Add an instruction handler, after the handlers of its type and the types
	 * before it.
	 *
	 * @param type
	 *        the kind of handler; {@link InstructionHandlerInfo.Type#Reactor}
	 *        is this service itself, so is not allowed
	 * @param handler
	 *        the handler
	 * @param name
	 *        gives the name of what in the handler gave an instruction its
	 *        status, such as a control ID, or {@code null}
	 * @throws IllegalArgumentException
	 *         if {@code type} is {@code Reactor}
	 */
	public void addHandler(InstructionHandlerInfo.Type type, InstructionHandler handler,
			Function<Instruction, @Nullable String> name) {
		if ( type == InstructionHandlerInfo.Type.Reactor ) {
			throw new IllegalArgumentException("The reactor is not added as a handler.");
		}
		int i = handlers.size();
		while ( i > 0 && handlers.get(i - 1).type.compareTo(type) > 0 ) {
			i--;
		}
		handlers.add(i, new Handler(type, handler, name));
	}

	/**
	 * Add a Control Conductor, after the other handlers.
	 *
	 * @param conductor
	 *        the conductor
	 */
	public void addConductor(ControlConductor conductor) {
		addHandler(InstructionHandlerInfo.Type.Conductor, conductor, _ -> conductor.getUid());
		conductors.add(conductor);
	}

	/**
	 * Get the Control Conductors.
	 *
	 * @return the conductors, in the order they handle instructions
	 */
	public List<ControlConductor> conductors() {
		return List.copyOf(conductors);
	}

	@Override
	public @Nullable InstructionStatus executeInstruction(Instruction instruction) {
		final int number = number(instruction);
		final String topic = instruction.getTopic();
		running.push(number);
		InstructionStatus status = null;
		String error = null;
		try {
			boolean handled = HANDLED_TOPICS.contains(topic);
			if ( handlesTopic(topic) ) {
				// the reactor is the first handler, as its service ranking is 1
				status = processInstruction(instruction);
				return status;
			}
			for ( Handler h : handlers ) {
				if ( !h.handler.handlesTopic(topic) ) {
					continue;
				}
				handled = true;
				status = h.handler.processInstruction(instruction);
				if ( status != null ) {
					handledBy.put(number, new InstructionHandlerInfo(h.type, h.name.apply(instruction)));
					break;
				}
			}
			if ( !handled ) {
				status = InstructionUtils.createStatus(instruction, InstructionState.Completed);
			}
			return status;
		} catch ( RuntimeException e ) {
			// SimpleInstructionExecutionService logs and throws handler errors
			error = (e.getMessage() != null ? e.getMessage() : e.toString());
			throw e;
		} finally {
			running.pop();
			final StoredInstruction s = stored.get(number);
			if ( s != null ) {
				s.tried = true;
				if ( status != null ) {
					s.state = status.getInstructionState();
				}
			}
			record(instruction, number, status != null ? status.getInstructionState() : null, status,
					error);
		}
	}

	@Override
	public boolean handlesTopic(@Nullable String topic) {
		return TOPIC_CANCEL_INSTRUCTION.equals(topic);
	}

	@Override
	public InstructionStatus processInstruction(Instruction instruction) {
		if ( TOPIC_CANCEL_INSTRUCTION.equals(instruction.getTopic()) ) {
			// SimpleReactorService handles CancelInstruction, even when deferred
			final int number = number(instruction);
			final InstructionStatus status = cancel(instruction);
			handledBy.put(number, new InstructionHandlerInfo(InstructionHandlerInfo.Type.Reactor, null));
			record(instruction, number, status.getInstructionState(), status, null);
			return status;
		}

		// SimpleReactorService stores a new instruction as Received
		final int number = number(instruction);
		StoredInstruction s = stored.get(number);
		if ( s == null ) {
			s = store(instruction, number, InstructionState.Received);
			record(instruction, number, s.state, null, null);
		}
		final InstructionState state = (s.state != null ? s.state : InstructionState.Received);
		return InstructionUtils.createStatus(instruction, state);
	}

	@Override
	public void storeInstruction(Instruction instruction) {
		final int number = number(instruction);
		final InstructionStatus status = instruction.getStatus();
		final StoredInstruction s = stored.get(number);
		if ( s != null ) {
			if ( status != null ) {
				s.state = status.getInstructionState();
				record(instruction, number, s.state, status, null);
			}
			return;
		}
		final StoredInstruction added = store(instruction, number,
				status != null ? status.getInstructionState() : InstructionState.Received);
		record(instruction, number, added.state, status, null);
	}

	private static InstructionStatus cancelError(Instruction cancel, boolean ignoreErrors,
			String message, String code) {
		return InstructionUtils.createStatus(cancel,
				ignoreErrors ? InstructionState.Completed : InstructionState.Declined,
				InstructionUtils.createErrorResultParameters(message, code));
	}

	/**
	 * Cancel a stored instruction, like SolarNode's
	 * {@code SimpleReactorService}.
	 *
	 * @param cancel
	 *        the {@code CancelInstruction} instruction
	 * @return the status
	 */
	private InstructionStatus cancel(Instruction cancel) {
		final boolean ignoreErrors = StringUtils
				.parseBoolean(cancel.getParameterValue(PARAM_IGNORE_ERRORS));
		final boolean force = StringUtils.parseBoolean(cancel.getParameterValue(PARAM_FORCE));
		final String idValue = cancel.getParameterValue(PARAM_ID);
		if ( idValue == null ) {
			return cancelError(cancel, ignoreErrors, "Missing 'id' parameter.", "SRS.0001");
		}
		final Long id;
		try {
			id = Long.valueOf(idValue);
		} catch ( NumberFormatException e ) {
			return cancelError(cancel, ignoreErrors, "Invalid 'id' parameter (not a number).",
					"SRS.0002");
		}
		boolean updated = false;
		try {
			final Integer number = numbers.get(key(cancel.getInstructorId(), id));
			final StoredInstruction target = (number != null ? stored.get(number) : null);
			if ( target == null ) {
				return cancelError(cancel, ignoreErrors, "Instruction with given 'id' not found.",
						"SRS.0003");
			}
			updated = decline(target, InstructionState.Received, String.format(
					"Instruction cancelled by %s Instruction %d", cancel.getTopic(), cancel.getId()));
			if ( !updated ) {
				return cancelError(cancel, ignoreErrors, "Instruction failed to update state.",
						"SRS.0004");
			}
		} finally {
			if ( updated || force ) {
				// also cancel any waiting children, such as a Control Conductor's tasks
				final String parentInstructorId = cancel.getInstructorId();
				for ( StoredInstruction child : List.copyOf(stored.values()) ) {
					final Instruction c = child.instruction;
					if ( parentInstructorId != null
							&& parentInstructorId
									.equals(c.getParameterValue(Instruction.PARAM_PARENT_INSTRUCTOR_ID))
							&& idValue.equals(
									c.getParameterValue(Instruction.PARAM_PARENT_INSTRUCTION_ID)) ) {
						decline(child, InstructionState.Received, String.format(
								"Cancelled instruction because of %s instruction %d that cancelled parent instruction %d",
								cancel.getTopic(), cancel.getId(), id));
					}
				}
			}
		}
		return InstructionUtils.createStatus(cancel, InstructionState.Completed);
	}

	/**
	 * Decline a stored instruction if it has an expected state, like
	 * {@code InstructionDao.compareAndStoreInstructionStatus()}.
	 */
	private boolean decline(StoredInstruction s, InstructionState expected, String message) {
		if ( s.state != expected ) {
			return false;
		}
		s.state = InstructionState.Declined;
		record(s.instruction, s.number, s.state, InstructionUtils.createStatus(s.instruction, s.state,
				InstructionUtils.createErrorResultParameters(message, null)), null);
		return true;
	}

	private StoredInstruction store(Instruction instruction, int number,
			@Nullable InstructionState state) {
		final Instant executionDate = instruction.getExecutionDate();
		final Supplier<@Nullable Issuer> i = this.issuer;
		final StoredInstruction s = new StoredInstruction(number, instruction,
				executionDate != null ? executionDate : clock.instant(), i != null ? i.get() : null,
				state);
		stored.put(number, s);
		return s;
	}

	/**
	 * Get the date the next waiting stored instruction is due.
	 *
	 * @return the date, or {@code null} if no instruction is waiting
	 */
	public @Nullable Instant nextDue() {
		Instant result = null;
		for ( StoredInstruction s : stored.values() ) {
			if ( s.waiting() && (result == null || s.due.isBefore(result)) ) {
				result = s.due;
			}
		}
		return result;
	}

	/**
	 * Get the waiting stored instructions due by a given date.
	 *
	 * @param date
	 *        the date
	 * @return the instructions, in the order they were stored, like the
	 *         Instruction Execution Job's instructor ID then ID order
	 */
	public List<StoredInstruction> dueBy(Instant date) {
		final List<StoredInstruction> result = new ArrayList<>(4);
		for ( StoredInstruction s : stored.values() ) {
			if ( s.waiting() && !s.due.isAfter(date) ) {
				result.add(s);
			}
		}
		return result;
	}

	/**
	 * Get the waiting stored instructions.
	 *
	 * @return the instructions, in the order the job would run them
	 */
	public List<InstructionInfo> waiting() {
		final List<StoredInstruction> list = new ArrayList<>(4);
		for ( StoredInstruction s : stored.values() ) {
			if ( s.waiting() ) {
				list.add(s);
			}
		}
		list.sort((l, r) -> {
			int c = l.due.compareTo(r.due);
			return (c != 0 ? c : Integer.compare(l.number, r.number));
		});
		final List<InstructionInfo> result = new ArrayList<>(list.size());
		for ( StoredInstruction s : list ) {
			result.add(info(s.instruction, s.number, s.state, null, null));
		}
		return result;
	}

	/**
	 * Get the information about a stored instruction.
	 *
	 * @param instruction
	 *        the stored instruction
	 * @return the information
	 */
	public InstructionInfo info(StoredInstruction instruction) {
		return info(instruction.instruction, instruction.number, instruction.state, null, null);
	}

	/**
	 * Get the time the Instruction Execution Job runs an instruction due at a
	 * given time.
	 *
	 * <p>
	 * Like the node's {@code 0/10 * * * * ?} cron schedule for {@code 10}, the
	 * job runs on every second of the minute that is a multiple of
	 * {@code seconds}, starting on the minute.
	 * </p>
	 *
	 * @param due
	 *        the time the instruction is due
	 * @param seconds
	 *        the job schedule, in seconds
	 * @return the first time the job runs on or after {@code due}
	 */
	public static Instant jobTick(Instant due, int seconds) {
		long epoch = due.getEpochSecond();
		if ( due.getNano() > 0 ) {
			epoch++;
		}
		final long minute = Math.floorDiv(epoch, 60L) * 60L;
		final long second = epoch - minute;
		final int step = Math.max(1, seconds);
		final long next = ((second + step - 1) / step) * step;
		return Instant.ofEpochSecond(next < 60 ? minute + next : minute + 60);
	}

	/**
	 * Run a stored instruction, like the Instruction Execution Job.
	 *
	 * @param instruction
	 *        the stored instruction
	 * @return the status, or {@code null} if no handler handled it
	 */
	public @Nullable InstructionStatus run(StoredInstruction instruction) {
		return executeInstruction(instruction.instruction);
	}

	private static String key(@Nullable String instructorId, @Nullable Object id) {
		return instructorId + "\n" + id;
	}

	private int number(Instruction instruction) {
		final Long id = instruction.getId();
		final Integer existing = (id != null ? numbers.get(key(instruction.getInstructorId(), id))
				: null);
		if ( existing != null ) {
			return existing;
		}
		final int number = nextNumber++;
		if ( id != null ) {
			numbers.put(key(instruction.getInstructorId(), id), number);
		}
		// the instruction that issued it: a Conductor task's parent, or the one running
		Integer parent = null;
		final String parentId = instruction.getParameterValue(Instruction.PARAM_PARENT_INSTRUCTION_ID);
		if ( parentId != null ) {
			parent = numbers.get(key(
					instruction.getParameterValue(Instruction.PARAM_PARENT_INSTRUCTOR_ID), parentId));
		}
		if ( parent == null ) {
			parent = running.peek();
		}
		parents.put(number, parent);
		return number;
	}

	private void record(Instruction instruction, int number, @Nullable InstructionState state,
			@Nullable InstructionStatus status, @Nullable String error) {
		final Consumer<InstructionInfo> l = this.listener;
		if ( l != null ) {
			l.accept(info(instruction, number, state, status, error));
		}
	}

	private InstructionInfo info(Instruction instruction, int number, @Nullable InstructionState state,
			@Nullable InstructionStatus status, @Nullable String error) {
		final Map<String, String> params = new LinkedHashMap<>(8);
		for ( String name : instruction.getParameterNames() ) {
			params.put(name, instruction.getParameterValue(name));
		}
		final String topic = instruction.getTopic();
		String message = error;
		String code = null;
		final Map<String, ?> results = (status != null ? status.getResultParameters() : null);
		if ( results != null ) {
			final Object m = results.get(InstructionStatus.MESSAGE_RESULT_PARAM);
			if ( m != null && message == null ) {
				message = m.toString();
			}
			final Object c = results.get(InstructionStatus.ERROR_CODE_RESULT_PARAM);
			if ( c != null ) {
				code = c.toString();
			}
		}
		final StoredInstruction s = stored.get(number);
		final Instant executeAt = (s != null && s.waiting() ? s.due : null);
		String conductor = null;
		Integer task = null;
		if ( InstructionHandler.TOPIC_SIGNAL.equals(topic) ) {
			for ( ControlConductor c : conductors ) {
				final String uid = c.getUid();
				final String taskNumber = (uid != null ? params.get(uid) : null);
				if ( taskNumber != null ) {
					conductor = uid;
					try {
						task = Integer.valueOf(taskNumber);
					} catch ( NumberFormatException e ) {
						// not a task number
					}
					break;
				}
			}
		}
		return new InstructionInfo(number, instruction.getId(), topic != null ? topic : "", params,
				state != null ? state.name() : null, parents.get(number), executeAt, message, code,
				conductor, task, handledBy.get(number));
	}

	/**
	 * Set the listener for issued instructions.
	 *
	 * @param listener
	 *        the listener to set, or {@code null}
	 */
	public void setListener(@Nullable Consumer<InstructionInfo> listener) {
		this.listener = listener;
	}

	/**
	 * Set the supplier of the filter step that stores an instruction.
	 *
	 * @param issuer
	 *        the supplier to set, or {@code null}
	 */
	public void setIssuer(@Nullable Supplier<@Nullable Issuer> issuer) {
		this.issuer = issuer;
	}

}
