/* ==================================================================
 * SimTaskScheduler.java - 19 Oct 2026 10:12:30 am
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

/**
 * A {@link TaskScheduler} that runs tasks on the simulated clock.
 *
 * <p>
 * SolarNode filters schedule tasks at a time computed from the clock they were
 * given, which in a simulation is the simulated clock, like
 * {@code clock.instant().plus(timeout)}, so a task is due at the time it is
 * scheduled for, or now if that time has passed. The simulation runs due tasks
 * between datum, in due order, with the simulated clock set to each task's due
 * time.
 * </p>
 *
 * <p>
 * A task scheduled with a {@link Trigger}, such as a datum source's poll job,
 * gets its times from the trigger, with a trigger context on the simulated
 * clock, which is updated after each run with the due time as the scheduled and
 * actual execution time.
 * </p>
 *
 * <p>
 * A node service's tasks, such as the operational modes service's auto-expire
 * task, are background tasks: they never end, so they are not pending work.
 * </p>
 *
 * @author matt
 * @version 1.3
 */
public final class SimTaskScheduler implements TaskScheduler {

	/** A scheduled task. */
	public final class Task implements ScheduledFuture<Object> {

		private final long seq;
		private final Runnable runnable;
		private final @Nullable Duration period;
		private final @Nullable Trigger trigger;
		private final @Nullable SimpleTriggerContext triggerContext;
		private final @Nullable String owner;
		private final boolean background;
		private Instant due;
		private boolean cancelled;
		private boolean done;

		private Task(long seq, Runnable runnable, Instant due, @Nullable Duration period,
				@Nullable Trigger trigger, @Nullable String owner, boolean background) {
			super();
			this.seq = seq;
			this.runnable = runnable;
			this.due = due;
			this.period = period;
			this.trigger = trigger;
			this.triggerContext = (trigger != null ? new SimpleTriggerContext(clock) : null);
			this.owner = owner;
			this.background = background;
		}

		/**
		 * Get the simulated time the task is due.
		 *
		 * @return the due time
		 */
		public Instant due() {
			return due;
		}

		/**
		 * Get the {@code id} of the filter that scheduled the task.
		 *
		 * @return the filter id, or {@code null} if not known
		 */
		public @Nullable String owner() {
			return owner;
		}

		/**
		 * Test if this is a background task, of a node service.
		 *
		 * @return {@code true} if the task was scheduled with
		 *         {@link SimTaskScheduler#background(Runnable)}
		 */
		public boolean background() {
			return background;
		}

		@Override
		public long getDelay(TimeUnit unit) {
			return unit.convert(Duration.between(clock.instant(), due));
		}

		@Override
		public int compareTo(Delayed o) {
			if ( o instanceof Task t ) {
				int c = due.compareTo(t.due);
				return (c != 0 ? c : Long.compare(seq, t.seq));
			}
			return Long.compare(getDelay(TimeUnit.NANOSECONDS), o.getDelay(TimeUnit.NANOSECONDS));
		}

		@Override
		public boolean cancel(boolean mayInterruptIfRunning) {
			if ( done ) {
				return false;
			}
			cancelled = true;
			done = true;
			tasks.remove(this);
			return true;
		}

		@Override
		public boolean isCancelled() {
			return cancelled;
		}

		@Override
		public boolean isDone() {
			return done;
		}

		@Override
		public @Nullable Object get() {
			return null;
		}

		@Override
		public @Nullable Object get(long timeout, TimeUnit unit) {
			return null;
		}

	}

	private final Clock clock;
	private final Supplier<@Nullable String> owner;
	private final PriorityQueue<Task> tasks = new PriorityQueue<>();
	private long nextSeq;
	private @Nullable Task running;
	private int background;

	/**
	 * Constructor.
	 *
	 * @param clock
	 *        the simulated clock
	 * @param owner
	 *        provides the {@code id} of the filter scheduling a task, when not
	 *        scheduled by a running task
	 */
	public SimTaskScheduler(Clock clock, Supplier<@Nullable String> owner) {
		super();
		this.clock = clock;
		this.owner = owner;
	}

	/**
	 * Get the next task.
	 *
	 * @return the next task to run, or {@code null} if there are none
	 */
	public @Nullable Task peek() {
		return tasks.peek();
	}

	/**
	 * Get the waiting tasks.
	 *
	 * @return the tasks, in due order
	 */
	public List<Task> pending() {
		List<Task> result = new ArrayList<>(tasks);
		result.sort(null);
		return result;
	}

	/**
	 * Run the next task.
	 *
	 * <p>
	 * The caller sets the simulated clock to the task's due time first.
	 * </p>
	 *
	 * @return {@code true} if a task ran
	 */
	public boolean runNext() {
		final Task task = tasks.poll();
		if ( task == null ) {
			return false;
		}
		running = task;
		try {
			task.runnable.run();
		} finally {
			running = null;
			final Duration period = task.period;
			final Trigger trigger = task.trigger;
			final SimpleTriggerContext triggerContext = task.triggerContext;
			Instant next = null;
			if ( task.cancelled ) {
				// done
			} else if ( period != null ) {
				next = task.due.plus(period);
			} else if ( trigger != null && triggerContext != null ) {
				triggerContext.update(task.due, task.due, clock.instant());
				next = trigger.nextExecution(triggerContext);
			}
			if ( next != null ) {
				task.due = next;
				tasks.add(task);
			} else {
				task.done = true;
			}
		}
		return true;
	}

	private Task add(Runnable runnable, Instant due, @Nullable Duration period,
			@Nullable Trigger trigger) {
		final Task current = running;
		final boolean bg = (current != null ? current.background : background > 0);
		final Task task = new Task(nextSeq++, runnable, due, period, trigger,
				bg ? null : current != null ? current.owner : owner.get(), bg);
		tasks.add(task);
		return task;
	}

	/**
	 * Run something that schedules background tasks, such as starting a node
	 * service.
	 *
	 * <p>
	 * The tasks it schedules, and the tasks they schedule, have no owner.
	 * </p>
	 *
	 * @param task
	 *        the task to run
	 */
	public void background(Runnable task) {
		background++;
		try {
			task.run();
		} finally {
			background--;
		}
	}

	/**
	 * Get the time a task is due: its start time, or now if that has passed.
	 */
	private Instant due(Instant startTime) {
		final Instant now = clock.instant();
		return (startTime.isBefore(now) ? now : startTime);
	}

	@Override
	public Clock getClock() {
		return clock;
	}

	@Override
	public @Nullable ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
		final Instant first = trigger.nextExecution(new SimpleTriggerContext(clock));
		if ( first == null ) {
			return null;
		}
		return add(task, due(first), null, trigger);
	}

	@Override
	public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
		return add(task, due(startTime), null, null);
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
		return add(task, due(startTime), period, null);
	}

	@Override
	public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
		return add(task, clock.instant(), period, null);
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
		return add(task, due(startTime), delay, null);
	}

	@Override
	public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
		return add(task, clock.instant(), delay, null);
	}

}
