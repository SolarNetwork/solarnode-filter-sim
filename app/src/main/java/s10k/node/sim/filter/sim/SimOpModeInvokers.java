/* ==================================================================
 * SimOpModeInvokers.java - 30 Oct 2026 3:20:18 pm
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.osgi.service.event.Event;
import org.springframework.context.MessageSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.PeriodicTrigger;
import s10k.node.sim.filter.registry.MessageSources;
import net.solarnetwork.node.datum.opmode.DatumDataSourceOpModeInvoker;
import net.solarnetwork.node.service.DatumDataSource;
import net.solarnetwork.node.service.DatumQueue;
import net.solarnetwork.node.service.MultiDatumDataSource;
import net.solarnetwork.node.service.OperationalModesService;
import net.solarnetwork.service.OptionalServiceCollection;
import net.solarnetwork.service.StaticOptionalService;

/**
 * The simulation's Operational Mode Datum Source Invokers.
 *
 * <p>
 * Each invoker is SolarNode's own {@link DatumDataSourceOpModeInvoker}, created
 * like its blueprint does, with the simulation's services. It hears of mode
 * changes from the operational modes service's events, and while its mode is
 * active it reads the datum sources that its configurations match on their
 * schedules, offering their datum to the Datum Queue.
 * </p>
 *
 * <p>
 * The invoker's tasks are background tasks, as they never end, and are not
 * owned by the filter that changed the mode. The {@link Listener} hears which
 * invoker is running, and which datum source it is reading, so the datum it
 * offers are polled datum of that datum source.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
public final class SimOpModeInvokers {

	/** The invoker factory UID. */
	public static final String FACTORY_UID = "net.solarnetwork.node.datum.opmode.invoker";

	/** The invoker's message source, from its blueprint. */
	public static final MessageSource MESSAGE_SOURCE = MessageSources.messageSource(
			"net.solarnetwork.node.datum.opmode.DatumDataSourceOpModeInvoker",
			"net.solarnetwork.service.support.BasicIdentifiable");

	/**
	 * API for hearing what the invokers do.
	 */
	public interface Listener {

		/**
		 * Called when an invoker starts or stops handling a mode change or
		 * running a task.
		 *
		 * @param invoker
		 *        the invoker, or {@code null} when it stops
		 */
		void invoking(@Nullable Invoker invoker);

		/**
		 * Called when an invoker running a task looks at a datum source, before
		 * it might read the datum source and offer its datum.
		 *
		 * @param source
		 *        the datum source, or {@code null} when the task ends
		 */
		void reading(SimDatumSources.@Nullable Source source);

	}

	/** A simulated invoker. */
	public static final class Invoker {

		private final String id;
		private final String label;
		private final DatumDataSourceOpModeInvoker invoker;
		private boolean active;
		private int runs;

		private Invoker(String id, String label, DatumDataSourceOpModeInvoker invoker) {
			super();
			this.id = id;
			this.label = label;
			this.invoker = invoker;
		}

		/**
		 * Get the {@code id} of the invoker configuration.
		 *
		 * @return the ID
		 */
		public String id() {
			return id;
		}

		/**
		 * Get the label of the invoker in messages, such as
		 * {@code The Operational Mode Invoker "Boost"}.
		 *
		 * @return the label
		 */
		public String label() {
			return label;
		}

		/**
		 * Get the invoker.
		 *
		 * @return the invoker
		 */
		public DatumDataSourceOpModeInvoker invoker() {
			return invoker;
		}

		/**
		 * Test if the invoker's mode was ever active.
		 *
		 * @return {@code true} if the mode was active when the invoker started,
		 *         or in a mode change event since
		 */
		public boolean active() {
			return active;
		}

		/**
		 * Get the number of times the invoker's tasks ran.
		 *
		 * @return the count
		 */
		public int runs() {
			return runs;
		}

		private @Nullable String mode() {
			final String mode = invoker.getOperationalMode();
			return (mode != null && !mode.isBlank() ? mode.toLowerCase(Locale.ROOT) : null);
		}

	}

	private final SimTaskScheduler scheduler;
	private final OperationalModesService opModesService;
	private final SimDatumSources datumSources;
	private final List<Invoker> invokers = new ArrayList<>(2);
	private @Nullable Listener listener;

	/**
	 * Constructor.
	 *
	 * @param scheduler
	 *        the task scheduler
	 * @param opModesService
	 *        the operational modes service
	 * @param datumSources
	 *        the datum sources the invokers read
	 */
	public SimOpModeInvokers(SimTaskScheduler scheduler, OperationalModesService opModesService,
			SimDatumSources datumSources) {
		super();
		this.scheduler = scheduler;
		this.opModesService = opModesService;
		this.datumSources = datumSources;
	}

	/**
	 * Set the listener.
	 *
	 * @param listener
	 *        the listener to set, or {@code null}
	 */
	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	/**
	 * Create an invoker, like its blueprint does.
	 *
	 * <p>
	 * The invoker still needs its settings, and to be started with
	 * {@link #start(Invoker, SimEventAdmin)}.
	 * </p>
	 *
	 * @param id
	 *        the {@code id} of the invoker configuration
	 * @param label
	 *        the label of the invoker in messages
	 * @param datumQueue
	 *        the Datum Queue
	 * @param executor
	 *        the node's task executor
	 * @return the invoker
	 */
	public Invoker add(String id, String label, DatumQueue datumQueue, TaskExecutor executor) {
		final Invoker[] holder = new Invoker[1];
		final DatumDataSourceOpModeInvoker invoker = new DatumDataSourceOpModeInvoker(
				new InvokerScheduler(holder), opModesService, new StaticOptionalService<>(datumQueue),
				new DataSources<>(DatumDataSource.class, false),
				new DataSources<>(MultiDatumDataSource.class, true));
		invoker.setMessageSource(MESSAGE_SOURCE);
		invoker.setTaskExecutor(executor);
		final Invoker result = new Invoker(id, label, invoker);
		holder[0] = result;
		invokers.add(result);
		return result;
	}

	/**
	 * Start an invoker, after its settings are applied: it activates if its
	 * mode is active, and hears of mode changes from then on.
	 *
	 * @param invoker
	 *        the invoker
	 * @param eventAdmin
	 *        the event admin to hear mode changes from
	 */
	public void start(Invoker invoker, SimEventAdmin eventAdmin) {
		run(invoker, () -> {
			final String mode = invoker.mode();
			if ( mode != null && opModesService.activeOperationalModes().contains(mode) ) {
				invoker.active = true;
			}
			invoker.invoker.serviceDidStartup();
		});
		eventAdmin.addModeHandler(event -> handleEvent(invoker, event));
	}

	private void handleEvent(Invoker invoker, Event event) {
		final String mode = invoker.mode();
		if ( mode != null && event.getProperty(
				OperationalModesService.EVENT_PARAM_ACTIVE_OPERATIONAL_MODES) instanceof Set<?> modes
				&& modes.contains(mode) ) {
			invoker.active = true;
		}
		run(invoker, () -> invoker.invoker.handleEvent(event));
	}

	/**
	 * Stop the invokers.
	 */
	public void shutdown() {
		for ( Invoker invoker : invokers ) {
			run(invoker, invoker.invoker::serviceDidShutdown);
		}
	}

	/**
	 * Get the invokers.
	 *
	 * @return the invokers, in the order they were added
	 */
	public List<Invoker> invokers() {
		return invokers;
	}

	/**
	 * Test if there are any invokers.
	 *
	 * @return {@code true} if there are none
	 */
	public boolean isEmpty() {
		return invokers.isEmpty();
	}

	private void run(Invoker invoker, Runnable task) {
		final Listener l = this.listener;
		if ( l != null ) {
			l.invoking(invoker);
		}
		try {
			task.run();
		} finally {
			if ( l != null ) {
				l.reading(null);
				l.invoking(null);
			}
		}
	}

	/**
	 * The datum sources an invoker reads, which tells the listener of each one
	 * the invoker looks at, as it reads a datum source and offers its datum
	 * before looking at the next.
	 *
	 * <p>
	 * The invoker has one collection of the {@link DatumDataSource} types'
	 * datum sources, and one of the multi datum source types', the
	 * {@link MultiDatumDataSource}s.
	 * </p>
	 */
	private final class DataSources<T> implements OptionalServiceCollection<T> {

		private final Class<T> type;
		private final boolean multi;

		private DataSources(Class<T> type, boolean multi) {
			super();
			this.type = type;
			this.multi = multi;
		}

		@Override
		public Iterable<T> services() {
			final List<SimDatumSources.Source> sources = datumSources.sources().stream()
					.filter(s -> s.type().multi() == multi && type.isInstance(s.datumSource())).toList();
			return () -> new Iterator<>() {

				private final Iterator<SimDatumSources.Source> itr = sources.iterator();

				@Override
				public boolean hasNext() {
					return itr.hasNext();
				}

				@Override
				public T next() {
					final SimDatumSources.Source source = itr.next();
					final Listener l = listener;
					if ( l != null ) {
						l.reading(source);
					}
					return type.cast(source.datumSource());
				}

			};
		}

	}

	/**
	 * The invoker's task scheduler: its tasks are background tasks, which tell
	 * the listener which invoker is running. A period of {@code 0}, which would
	 * run without a pause, is not scheduled.
	 */
	private final class InvokerScheduler implements TaskScheduler {

		private final Invoker[] holder;

		private InvokerScheduler(Invoker[] holder) {
			super();
			this.holder = holder;
		}

		private Runnable task(Runnable task) {
			return () -> {
				final Invoker invoker = holder[0];
				invoker.runs++;
				run(invoker, task);
			};
		}

		@Override
		public @Nullable ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
			if ( trigger instanceof PeriodicTrigger p && p.getPeriodDuration().isZero() ) {
				return null;
			}
			return background(() -> scheduler.schedule(task(task), trigger));
		}

		@Override
		public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
			return background(() -> scheduler.schedule(task(task), startTime));
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime,
				Duration period) {
			return background(() -> scheduler.scheduleAtFixedRate(task(task), startTime, period));
		}

		@Override
		public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
			return background(() -> scheduler.scheduleAtFixedRate(task(task), period));
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime,
				Duration delay) {
			return background(() -> scheduler.scheduleWithFixedDelay(task(task), startTime, delay));
		}

		@Override
		public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
			return background(() -> scheduler.scheduleWithFixedDelay(task(task), delay));
		}

		@SuppressWarnings("unchecked")
		private <T extends @Nullable ScheduledFuture<?>> T background(Supplier<T> schedule) {
			final Object[] result = new Object[1];
			scheduler.background(() -> result[0] = schedule.get());
			return (T) result[0];
		}

	}

}
