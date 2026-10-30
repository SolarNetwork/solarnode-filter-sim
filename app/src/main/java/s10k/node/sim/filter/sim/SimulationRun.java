/* ==================================================================
 * SimulationRun.java - 18 Oct 2026 8:56:20 am
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

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.SimpleTriggerContext;
import net.solarnetwork.domain.datum.DatumSamplesType;
import net.solarnetwork.domain.NodeControlPropertyType;
import net.solarnetwork.domain.SimpleLocation;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.node.control.conductor.ControlConductor;
import net.solarnetwork.node.control.conductor.ControlTaskConfig;
import net.solarnetwork.node.datum.battery.mock.MockBatteryDatumDataSource;
import net.solarnetwork.node.datum.opmode.DatumDataSourceOpModeInvoker;
import net.solarnetwork.node.datum.opmode.DatumDataSourceScheduleConfig;
import net.solarnetwork.node.domain.LocalState;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.job.JobUtils;
import net.solarnetwork.node.metrics.domain.Metric;
import net.solarnetwork.node.reactor.InstructionHandler;
import net.solarnetwork.node.runtime.DatumHistory;
import net.solarnetwork.node.runtime.DefaultLocalStateService;
import net.solarnetwork.node.runtime.DefaultOperationalModesService;
import net.solarnetwork.node.service.DatumHistorian;
import net.solarnetwork.node.service.NodeControlProvider;
import net.solarnetwork.node.service.DatumQueueProcessObserver.Stage;
import net.solarnetwork.node.service.support.DatumFilterChainService;
import net.solarnetwork.node.service.support.ExpressionConfig;
import net.solarnetwork.node.service.support.SettingsPlaceholderService;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ExpressionService;
import net.solarnetwork.service.StaticOptionalService;
import s10k.node.sim.filter.domain.ControlConductorConfig;
import s10k.node.sim.filter.domain.ControlKind;
import s10k.node.sim.filter.domain.ControlPublishMode;
import s10k.node.sim.filter.domain.DatumInfo;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.DatumSourceConfig;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.InstructionInfo;
import s10k.node.sim.filter.domain.LocalStateInfo;
import s10k.node.sim.filter.domain.MetricInfo;
import s10k.node.sim.filter.domain.NodeControlConfig;
import s10k.node.sim.filter.domain.NodeLocation;
import s10k.node.sim.filter.domain.OpModeInvokerConfig;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationHistory;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.domain.SimulationState;
import s10k.node.sim.filter.domain.SimulationStats;
import s10k.node.sim.filter.domain.UserMetadataConfig;
import s10k.node.sim.filter.registry.DatumSourceTypeDefinition;
import s10k.node.sim.filter.registry.DatumSourceTypeRegistry;
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.registry.UnavailableFilterType;
import s10k.node.sim.filter.settings.SettingsApplier;
import s10k.node.sim.filter.sim.FilterTracer.DatumRecord;
import s10k.node.sim.filter.sim.FilterTracer.RunRecord;
import s10k.node.sim.filter.sim.FilterTracer.StepRecord;
import tools.jackson.databind.JsonNode;

/**
 * One simulation run.
 *
 * <p>
 * The Datum Queue is simulated the way SolarNode's {@code DefaultDatumQueue}
 * processes datum, one at a time in FIFO order: add the datum to the unfiltered
 * history, apply the entry filter, then add the result to the (filtered)
 * history. Datum that filters generate are added to the end of the queue.
 * </p>
 *
 * <p>
 * On a node, input datum arrive over time, so the datum a filter generates are
 * processed before the next input arrives. The simulation therefore queues each
 * input datum only once the queue is empty.
 * </p>
 *
 * @author matt
 * @version 1.1
 */
final class SimulationRun implements SimulatedDatumQueue.Sink {

	/** The {@code uid} setting key. */
	private static final String UID_SETTING = "uid";

	/** The chain member setting keys, which are generated from the config. */
	private static final String TRANSFORM_UIDS_SETTING = "transformUids";

	/** A limit on how far back to look in the datum history. */
	private static final int MAX_HISTORY_OFFSET = 1000;

	private record QueuedDatum(int seq, NodeDatum datum, boolean persist, int depth,
			DatumOrigin origin) {

	}

	/**
	 * What a datum's generation path needs of it, kept for every datum, as the
	 * datum themselves would take too much memory in a long run.
	 */
	private record Lineage(@Nullable String sourceId, @Nullable Integer parentSeq) {

	}

	/** Repeated log messages of a filter. */
	private static final class LogRecord {

		private final @Nullable String filterId;
		/** What logged the message when it was not a filter. */
		private final @Nullable String source;
		/** The datum source that logged the message. */
		private final @Nullable String datumSourceId;
		private final @Nullable Integer datumSeq;
		private final boolean error;
		private final String message;
		private int count = 1;

		private LogRecord(@Nullable String filterId, @Nullable String source,
				@Nullable String datumSourceId, @Nullable Integer datumSeq, boolean error,
				String message) {
			this.filterId = filterId;
			this.source = source;
			this.datumSourceId = datumSourceId;
			this.datumSeq = datumSeq;
			this.error = error;
			this.message = message;
		}

	}

	private record DepthLimit(@Nullable String filterId, @Nullable Integer datumSeq, String sourceId,
			String path, int count) {

	}

	private final FilterTypeRegistry registry;
	private final DatumSourceTypeRegistry datumSourceTypes;
	private final ExpressionService expressionService;
	private final SimulationRequest request;
	private final SimulationOptions options;
	private final SimulationLimits.RunLimits limits;
	private final List<NodeDatum> input;
	private final SimulationContext context;
	private final FilterTracer tracer;
	private final List<SimulationMessage> messages = new ArrayList<>(8);
	private final Deque<QueuedDatum> queue = new ArrayDeque<>(16);
	private final Map<Integer, Lineage> lineage = new HashMap<>(16);
	/** Gets each datum's trace once it is processed. */
	private SimulationObserver observer = _ -> {
	};
	private int processedCount;
	private @Nullable Integer lastSeq;
	private final Map<String, DepthLimit> depthLimits = new LinkedHashMap<>(2);
	private final Map<String, LogRecord> logs = new LinkedHashMap<>(4);

	private @Nullable QueuedDatum current;
	/** The index of the next input datum to queue. */
	private int nextInput;
	/** The filter being created or started, for its scheduled tasks. */
	private @Nullable String creating;
	/** The scheduled task running, and the datum it generated. */
	private SimTaskScheduler.@Nullable Task firing;
	/** The datum source being created or polled, or read by an invoker. */
	private SimDatumSources.@Nullable Source polling;
	/** The Operational Mode Datum Source Invoker being created or running. */
	private SimOpModeInvokers.@Nullable Invoker invoking;
	private final Map<String, DatumSourceConfig> datumSourceConfigs = new HashMap<>(4);
	/** The datum sources an invoker read. */
	private final Set<String> invokedSources = new HashSet<>(4);
	private int polledCount;
	private final List<QueuedDatum> taskGenerated = new ArrayList<>(2);
	private int taskRuns;
	/** The Instruction Execution Job schedule, and the instructions it ran. */
	private final int jobSeconds;
	private final List<RunRecord> runs = new ArrayList<>(4);
	private @Nullable Instant lastJobTick;
	private int nextSeq = 1;
	private int generatedCount;
	private int outputCount;
	private int discardedCount;

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param datumSourceTypes
	 *        the datum source type registry
	 * @param expressionService
	 *        the expression service
	 * @param allowedCommands
	 *        the external commands filters may run, in addition to those the
	 *        request allows
	 * @param limits
	 *        the server's limits
	 * @param request
	 *        the request
	 * @throws SimulationException
	 *         if the request input datum or metadata are not valid
	 */
	SimulationRun(FilterTypeRegistry registry, DatumSourceTypeRegistry datumSourceTypes,
			ExpressionService expressionService, List<String> allowedCommands, SimulationLimits limits,
			SimulationRequest request) {
		super();
		this.registry = registry;
		this.datumSourceTypes = datumSourceTypes;
		this.request = request;
		this.options = (request.options() != null ? request.options() : SimulationOptions.DEFAULTS);
		this.limits = limits.runLimits(options);
		this.input = parseInput();
		final NodeDatum first = (input.isEmpty() ? null : input.getFirst());
		final Instant firstDate = (first != null ? first.getTimestamp() : null);
		final List<String> commands = new ArrayList<>(allowedCommands);
		if ( options.allowedCommands() != null ) {
			commands.addAll(options.allowedCommands());
		}
		this.expressionService = expressionService;
		this.jobSeconds = options.instructionJobSecondsValue();
		this.context = new SimulationContext(firstDate != null ? firstDate : defaultStartDate(),
				expressionService, this, nodeMetadata(), commands);
		// placeholders first, as datum source control IDs can have them
		loadPlaceholders();
		loadControls();
		loadDatumSources();
		loadInvokers();
		loadConductors();
		registerUserMetadataServices();
		loadNodeLocation();
		loadDatumMetadata();
		this.tracer = new FilterTracer(context.opModesService());
		context.setTaskOwner(() -> {
			final StepRecord step = tracer.currentStep();
			return (step != null ? step.filterId : creating);
		});
		context.localStateDao().setChangeListener(tracer::localStateChanged);
		context.instructionService().setListener(tracer::instructionIssued);
		context.instructionService().setIssuer(() -> {
			final StepRecord step = tracer.currentStep();
			if ( step != null ) {
				final QueuedDatum datum = this.current;
				return new SimInstructionService.Issuer(datum != null ? datum.seq() : null, step.id,
						step.filterId);
			}
			// a conductor task stored while the job runs its parent instruction
			final RunRecord run = tracer.currentRun();
			return (run != null ? run.issuer : null);
		});
		context.metricDao().setListener(m -> tracer.metricSaved(metricInfo(m)));
		context.commandExecutor().setListener(tracer::commandRun);
		context.controlService().setListener(tracer::controlUpdated);
		context.controlService()
				.setPublisher((controlId, datum, persist) -> offer(datum, persist, controlId));
		context.modeSwitches().setListener(tracer::controlUpdated);
		context.datumSources().setListener(tracer::controlUpdated);
		context.invokers().setListener(new SimOpModeInvokers.Listener() {

			@Override
			public void invoking(SimOpModeInvokers.@Nullable Invoker invoker) {
				invoking = invoker;
			}

			@Override
			public void reading(SimDatumSources.@Nullable Source source) {
				polling = source;
			}

		});
		context.eventAdmin()
				.setPublisher((controlId, datum, persist) -> offer(datum, persist, controlId));
	}

	/**
	 * Run the simulation.
	 *
	 * @param observer
	 *        gets each datum's trace once it is processed, and can cancel the
	 *        run
	 * @return the result, without the datum traces, which only the observer
	 *         gets
	 * @throws SimulationException
	 *         if the request is not valid
	 */
	SimulationResult run(SimulationObserver observer) {
		this.observer = observer;
		FilterLogCapture.install();
		FilterLogCapture.capture(this::logged);
		try {
			return doRun();
		} finally {
			FilterLogCapture.capture(null);
		}
	}

	private SimulationResult doRun() {
		final long start = System.nanoTime();
		final Map<String, TracingDatumFilterService> filters = createFilters();
		final TracingDatumFilterService entry = entryFilter(filters);
		final Instant firstDate = context.simulatedClock().instant();
		loadEnvironment(firstDate);
		loadHistory(firstDate);
		context.startOperationalModes();
		startInvokers();

		final long deadline = start + TimeUnit.MILLISECONDS.toNanos(limits.timeoutMs());
		final int maxDatum = limits.maxDatum();
		if ( limits.datumLimited() ) {
			warn(SimulationMessage.CODE_DATUM_LIMIT,
					"The Maximum Datum of %d is more than the simulator allows, so %d was used."
							.formatted(limits.requestedMaxDatum(), maxDatum),
					null, null);
		}
		final Instant runUntil = runUntil();
		final SimTaskScheduler scheduler = context.taskScheduler();
		final int polls = startDatumSources(firstDate, runUntil, maxDatum);
		observer.started(Math.min(input.size() + polls, maxDatum));
		while ( true ) {
			// run scheduled tasks and the Instruction Execution Job due before the next datum,
			// or by the end time; a task first when both are due at the same time
			final NodeDatum next = nextDatum();
			final Instant nextTime = (next != null ? timestamp(next) : runUntil);
			final SimTaskScheduler.Task task = scheduler.peek();
			final Instant jobTick = nextJobTick();
			final boolean job = (jobTick != null && (task == null || jobTick.isBefore(task.due())));
			final Instant due = (job ? jobTick : task != null ? task.due() : null);
			if ( due != null && nextTime != null
					&& (next != null ? due.isBefore(nextTime) : !due.isAfter(nextTime)) ) {
				if ( taskRuns >= limits.maxTaskRuns() ) {
					warn(SimulationMessage.CODE_TASK_LIMIT,
							"Stopped after running %d scheduled tasks and stored instructions, the maximum allowed."
									.formatted(limits.maxTaskRuns()),
							null, null);
					break;
				}
				if ( observer.cancelled() ) {
					cancelled();
					break;
				}
				if ( job ) {
					runInstructions(due);
				} else if ( task != null ) {
					runTask(scheduler, task);
				}
				continue;
			}
			if ( next == null ) {
				break;
			}
			if ( processedCount >= maxDatum ) {
				warn(SimulationMessage.CODE_DATUM_LIMIT,
						"Stopped after processing %d datum, the maximum allowed, so %d datum were not processed. Filters that keep generating datum can cause this."
								.formatted(maxDatum, waiting()),
						null, null);
				break;
			}
			if ( System.nanoTime() > deadline ) {
				warn(SimulationMessage.CODE_TIMEOUT,
						"Stopped after %d ms, the maximum allowed, so %d datum were not processed."
								.formatted(limits.timeoutMs(), waiting()),
						null, null);
				break;
			}
			if ( observer.cancelled() ) {
				cancelled();
				break;
			}
			if ( queue.isEmpty() ) {
				enqueue(input.get(nextInput), true, 0, DatumOrigin.input(nextInput));
				nextInput++;
			}
			process(queue.removeFirst(), entry);
		}

		addPendingTaskMessages(scheduler, filters);
		addPendingInstructionMessages();
		addDatumSourceMessages(firstDate);
		addInvokerMessages();
		shutdown(filters);
		context.invokers().shutdown();
		shutdownDatumSources();
		context.stopOperationalModes();
		addDepthLimitMessages();
		addLogMessages(filters);

		final SimulationState finalState = snapshot(true, null);
		final long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		return new SimulationResult(List.of(), runs.stream().map(RunRecord::toRun).toList(),
				List.copyOf(messages), finalState, new SimulationStats(processedCount, generatedCount,
						outputCount, discardedCount, durationMs, polledCount));
	}

	private void cancelled() {
		final int waiting = waiting();
		messages.add(SimulationMessage.info(SimulationMessage.CODE_CANCELLED,
				waiting > 0
						? "Cancelled after processing %d datum, so %d datum were not processed."
								.formatted(processedCount, waiting)
						: "Cancelled after processing %d datum.".formatted(processedCount),
				null));
	}

	/**
	 * Get the next time the Instruction Execution Job runs a stored
	 * instruction.
	 *
	 * @return the time, or {@code null} if no instruction is waiting
	 */
	private @Nullable Instant nextJobTick() {
		final Instant due = context.instructionService().nextDue();
		if ( due == null ) {
			return null;
		}
		Instant tick = SimInstructionService.jobTick(due, jobSeconds);
		final Instant last = this.lastJobTick;
		if ( last != null && !tick.isAfter(last) ) {
			// stored while the job ran, so found by its next run
			tick = SimInstructionService.jobTick(last.plusSeconds(1), jobSeconds);
		}
		return tick;
	}

	/**
	 * Run the stored instructions due by a time of the Instruction Execution
	 * Job, like the job does. Datum they publish are processed next, before
	 * datum due after them.
	 */
	private void runInstructions(Instant tick) {
		context.simulatedClock().setInstant(tick);
		lastJobTick = tick;
		final SimInstructionService service = context.instructionService();
		final Integer afterSeq = lastSeq;
		for ( SimInstructionService.StoredInstruction s : service.dueBy(tick) ) {
			taskRuns++;
			final RunRecord run = new RunRecord(tick, afterSeq, service.info(s), s.issuer());
			runs.add(run);
			tracer.beginRun(run);
			try {
				service.run(s);
			} catch ( RuntimeException e ) {
				run.error = (e.getMessage() != null ? e.getMessage() : e.toString());
				warn(SimulationMessage.CODE_INSTRUCTION_ERROR,
						"Error running the stored instruction %s: %s"
								.formatted(instructionLabel(run.instruction), run.error),
						run.issuer != null ? run.issuer.filterId() : null, null);
			} finally {
				tracer.endRun();
			}
		}
		for ( int i = taskGenerated.size() - 1; i >= 0; i-- ) {
			queue.addFirst(taskGenerated.get(i));
		}
		taskGenerated.clear();
	}

	private static String instructionLabel(InstructionInfo instruction) {
		final String conductor = instruction.conductor();
		if ( conductor != null && instruction.task() != null ) {
			return "#%d %s (task %d of the Control Conductor %s)".formatted(instruction.number(),
					instruction.topic(), instruction.task(), SimulationMessage.quoted(conductor));
		}
		return "#%d %s".formatted(instruction.number(), instruction.topic());
	}

	/**
	 * Get a label for what runs an instruction, for its log messages.
	 *
	 * @param instruction
	 *        the instruction the job is running
	 * @return the label
	 */
	private static String runLabel(InstructionInfo instruction) {
		String conductor = instruction.conductor();
		if ( conductor == null
				&& ControlConductor.TOPIC_ORCHESTRATE_CONTROLS.equals(instruction.topic()) ) {
			conductor = instruction.parameters().get(InstructionHandler.PARAM_SERVICE);
		}
		if ( conductor != null ) {
			return "The Control Conductor " + SimulationMessage.quoted(conductor);
		}
		return "The stored instruction " + instructionLabel(instruction);
	}

	private void addPendingInstructionMessages() {
		final Map<String, List<InstructionInfo>> groups = new LinkedHashMap<>(4);
		for ( InstructionInfo info : context.instructionService().waiting() ) {
			groups.computeIfAbsent(info.conductor() != null ? info.conductor() : "",
					_ -> new ArrayList<>(2)).add(info);
		}
		for ( Map.Entry<String, List<InstructionInfo>> e : groups.entrySet() ) {
			final List<InstructionInfo> list = e.getValue();
			final InstructionInfo first = list.getFirst();
			final String text;
			if ( !e.getKey().isEmpty() ) {
				text = (list.size() == 1
						? "The Control Conductor %1$s has a task due at %3$s, after the simulation ended."
						: "The Control Conductor %1$s has %2$d tasks due after the simulation ended, the first at %3$s.")
								.formatted(SimulationMessage.quoted(e.getKey()), list.size(),
										first.executeAt());
			} else {
				text = (list.size() == 1
						? "The stored instruction %3$s is due at %2$s, after the simulation ended."
						: "%1$d stored instructions are due after the simulation ended, the first at %2$s.")
								.formatted(list.size(), first.executeAt(), instructionLabel(first));
			}
			messages.add(SimulationMessage.info(SimulationMessage.CODE_PENDING_INSTRUCTIONS, text
					+ " Keep running after the last datum to let the Instruction Execution Job run them.",
					null));
		}
	}

	/**
	 * Get the next datum to process.
	 *
	 * @return the first queued datum, or else the next input datum, or
	 *         {@code null} if there are none
	 */
	private @Nullable NodeDatum nextDatum() {
		final QueuedDatum queued = queue.peekFirst();
		if ( queued != null ) {
			return queued.datum();
		}
		return (nextInput < input.size() ? input.get(nextInput) : null);
	}

	/**
	 * Get the number of datum waiting to be processed.
	 *
	 * @return the queued datum plus the input datum not queued yet
	 */
	private int waiting() {
		return queue.size() + input.size() - nextInput;
	}

	/**
	 * Get the time the simulation runs until, after the last input datum.
	 *
	 * @return the time, or {@code null} to stop after the last datum
	 */
	private @Nullable Instant runUntil() {
		final Duration after = options.runAfterValue();
		if ( after.isZero() ) {
			return null;
		}
		Instant last = null;
		for ( NodeDatum d : input ) {
			final Instant ts = d.getTimestamp();
			if ( ts != null && (last == null || ts.isAfter(last)) ) {
				last = ts;
			}
		}
		return (last != null ? last : context.simulatedClock().instant()).plus(after);
	}

	/**
	 * Run a scheduled task at its due time. Datum it generates are processed
	 * next, before datum due after them.
	 */
	private void runTask(SimTaskScheduler scheduler, SimTaskScheduler.Task task) {
		context.simulatedClock().setInstant(task.due());
		taskRuns++;
		firing = task;
		try {
			scheduler.runNext();
		} catch ( RuntimeException e ) {
			warn(SimulationMessage.CODE_FILTER_ERROR,
					task.owner() != null
							? "Error in a scheduled task of the filter %s: %s"
									.formatted(SimulationMessage.quoted(task.owner()), e.toString())
							: "Error in a scheduled task: " + e,
					task.owner(), null);
		} finally {
			firing = null;
		}
		for ( int i = taskGenerated.size() - 1; i >= 0; i-- ) {
			queue.addFirst(taskGenerated.get(i));
		}
		taskGenerated.clear();
	}

	/**
	 * Record a message a filter logged, attributed to the filter running, or to
	 * the Control Conductor whose stored instruction the job is running.
	 */
	private void logged(boolean error, String message) {
		// an invoker can hear of a mode change while a filter runs
		final SimOpModeInvokers.Invoker invoker = invoking;
		final StepRecord step = (invoker == null ? tracer.currentStep() : null);
		final SimTaskScheduler.Task task = (invoker == null ? firing : null);
		final RunRecord run = (invoker == null ? tracer.currentRun() : null);
		final SimDatumSources.Source datumSource = (step == null ? polling : null);
		final String source = (datumSource != null ? datumSource.label()
				: invoker != null ? invoker.label()
						: step == null && task == null && run != null ? runLabel(run.instruction)
								: null);
		final String sourceId = (datumSource != null ? datumSource.id()
				: invoker != null ? invoker.id() : null);
		final String filterId = (step != null ? step.filterId
				: source != null ? null : task != null ? task.owner() : creating);
		final QueuedDatum datum = this.current;
		final String text = LogMessages.shorten(message);
		// repeats of a problem are counted together, with the first one's text
		final String key = (source != null ? source : filterId) + "\n" + LogMessages.problem(text);
		final LogRecord existing = logs.get(key);
		if ( existing != null ) {
			existing.count++;
		} else {
			logs.put(key, new LogRecord(filterId, source, sourceId, datum != null ? datum.seq() : null,
					error, text));
		}
	}

	private void addLogMessages(Map<String, TracingDatumFilterService> filters) {
		for ( LogRecord log : logs.values() ) {
			final TracingDatumFilterService filter = (log.filterId != null ? filters.get(log.filterId)
					: null);
			final String text = "%s logged: %s%s".formatted(
					filter != null ? label(filter) : log.source != null ? log.source : "A filter",
					log.message, log.count > 1 ? " (%d times)".formatted(log.count) : "");
			messages.add((log.error
					? SimulationMessage.error(SimulationMessage.CODE_FILTER_LOG, text, log.filterId)
					: SimulationMessage.warning(SimulationMessage.CODE_FILTER_LOG, text, log.filterId,
							log.datumSeq)).withDatumSourceId(log.datumSourceId));
		}
	}

	private void addPendingTaskMessages(SimTaskScheduler scheduler,
			Map<String, TracingDatumFilterService> filters) {
		final Map<String, Instant> next = new LinkedHashMap<>(4);
		for ( SimTaskScheduler.Task task : scheduler.pending() ) {
			if ( task.background() ) {
				// a node service's task, which never ends
				continue;
			}
			next.putIfAbsent(task.owner() != null ? task.owner() : "", task.due());
		}
		for ( Map.Entry<String, Instant> e : next.entrySet() ) {
			final String filterId = (e.getKey().isEmpty() ? null : e.getKey());
			final TracingDatumFilterService filter = (filterId != null ? filters.get(filterId) : null);
			messages.add(SimulationMessage.info(SimulationMessage.CODE_PENDING_TASKS,
					"The filter %s has a scheduled task due at %s, after the simulation ended. Keep running after the last datum to let it run."
							.formatted(filter != null ? label(filter) : "(unknown)", e.getValue()),
					filterId));
		}
	}

	/* === Input === */

	private Instant defaultStartDate() {
		Instant date = options.startDate();
		return (date != null ? date : Instant.now().truncatedTo(ChronoUnit.SECONDS));
	}

	private List<NodeDatum> parseInput() {
		final List<JsonNode> nodes = (request.datum() != null ? request.datum() : List.of());
		if ( nodes.size() > limits.maxDatum() ) {
			throw new SimulationException("Too many input datum: %d (the maximum is %d)."
					.formatted(nodes.size(), limits.maxDatum()));
		}
		final Duration interval = options.datumIntervalValue();
		final List<NodeDatum> result = new ArrayList<>(nodes.size());
		Instant next = defaultStartDate();
		for ( int i = 0; i < nodes.size(); i++ ) {
			Datum d = DatumJson.parse(nodes.get(i), "Datum " + (i + 1));
			NodeDatum nd = DatumJson.nodeDatum(d, next);
			Instant ts = nd.getTimestamp();
			next = (ts != null ? ts : next).plus(interval);
			result.add(nd);
		}
		return result;
	}

	/* === Filters === */

	private Map<String, TracingDatumFilterService> createFilters() {
		final SimConfig simConfig = request.config();
		final Map<String, FilterConfig> configs = new LinkedHashMap<>(8);
		for ( FilterConfig c : simConfig.filtersOrEmpty() ) {
			if ( c == null || c.id() == null || c.id().isBlank() ) {
				throw new SimulationException("Every filter configuration must have an id.");
			}
			if ( c.factoryUid() == null || c.factoryUid().isBlank() ) {
				throw new SimulationException(
						"The filter configuration %s has no factoryUid.".formatted(c.id()));
			}
			if ( configs.putIfAbsent(c.id(), c) != null ) {
				throw new SimulationException(
						"More than one filter configuration has the id %s.".formatted(c.id()));
			}
		}

		final Map<String, TracingDatumFilterService> result = new LinkedHashMap<>(configs.size());
		for ( FilterConfig config : configs.values() ) {
			final TracingDatumFilterService filter = createFilter(config);
			result.put(config.id(), filter);
			context.filterServices().add(filter);
		}
		configureChains(configs, result);
		checkUids(result);
		checkChainCycles(configs, result);
		return result;
	}

	private TracingDatumFilterService createFilter(FilterConfig config) {
		final FilterTypeDefinition def = registry.definition(config.factoryUid());
		final Map<String, String> settings = filterSettings(config, def);
		if ( def == null ) {
			if ( config.isUnknown() ) {
				warn(SimulationMessage.CODE_UNKNOWN_FILTER,
						"The settings of the filter %s are not part of the configuration, so datum pass through it unchanged."
								.formatted(label(config.id(), settings)),
						config.id(), null);
			} else {
				final UnavailableFilterType unavailable = registry.unavailable(config.factoryUid());
				if ( unavailable != null ) {
					warn(SimulationMessage.CODE_UNSUPPORTED_FILTER,
							"The filter type %s (%s) is not available, so datum pass through the filter %s unchanged. %s"
									.formatted(unavailable.name(), config.factoryUid(),
											label(config.id(), settings), unavailable.reason()),
							config.id(), null);
				} else {
					warn(SimulationMessage.CODE_UNSUPPORTED_FILTER,
							"The filter type %s is not supported, so datum pass through the filter %s unchanged."
									.formatted(config.factoryUid(), label(config.id(), settings)),
							config.id(), null);
				}
			}
			return new TracingDatumFilterService(config.id(), config.factoryUid(),
					new UnsupportedDatumFilterService(config.factoryUid(), settings.get(UID_SETTING)),
					false, tracer);
		}
		creating = config.id();
		final DatumFilterService filter = def.factory().create(context);
		context.injectServices(filter, def.messageSource());
		for ( SettingsApplier.Problem p : SettingsApplier.apply(filter, settings) ) {
			if ( p.unknown() ) {
				warn(SimulationMessage.CODE_UNKNOWN_SETTING, "The filter %s has no setting %s."
						.formatted(label(config.id(), settings), p.key()), config.id(), null);
			} else {
				warn(SimulationMessage.CODE_INVALID_SETTING,
						"The filter %s setting %s could not be applied: %s".formatted(
								label(config.id(), settings), p.key(), p.message()),
						config.id(), null);
			}
		}
		lifecycle(def.init(), filter, config.id(), "start");
		creating = null;
		context.registerService(filter);
		final String limitation = def.limitations(settings);
		if ( limitation != null ) {
			warn(SimulationMessage.CODE_FILTER_LIMITATION, limitation, config.id(), null);
		}
		final String problem = def.check(filter, context);
		if ( problem != null ) {
			warn(SimulationMessage.CODE_FILTER_PROBLEM, problem, config.id(), null);
		}
		return new TracingDatumFilterService(config.id(), config.factoryUid(), filter, def.isChain(),
				tracer, def.classLoader());
	}

	private static Map<String, String> filterSettings(FilterConfig config,
			@Nullable FilterTypeDefinition def) {
		final boolean chain = (def != null && def.isChain());
		final Map<String, String> result = new LinkedHashMap<>(config.settingsOrEmpty().size());
		for ( Map.Entry<String, String> e : config.settingsOrEmpty().entrySet() ) {
			final String key = e.getKey();
			if ( key == null || e.getValue() == null ) {
				continue;
			}
			if ( chain && key.startsWith(TRANSFORM_UIDS_SETTING) ) {
				// chain members come from the filterIds
				continue;
			}
			result.put(key, e.getValue());
		}
		return result;
	}

	private void lifecycle(@Nullable Consumer<DatumFilterService> method, DatumFilterService filter,
			String filterId, String action) {
		if ( method == null ) {
			return;
		}
		try {
			method.accept(filter);
		} catch ( RuntimeException e ) {
			warn(SimulationMessage.CODE_FILTER_ERROR, "Error trying to %s the filter %s: %s".formatted(
					action, SimulationMessage.quoted(filterId), e.toString()), filterId, null);
		}
	}

	private void configureChains(Map<String, FilterConfig> configs,
			Map<String, TracingDatumFilterService> filters) {
		for ( FilterConfig config : configs.values() ) {
			final TracingDatumFilterService traced = filters.get(config.id());
			if ( traced == null || !(traced.delegate() instanceof DatumFilterChainService chain) ) {
				continue;
			}
			final List<@Nullable String> uids = new ArrayList<>(config.filterIdsOrEmpty().size());
			for ( String memberId : config.filterIdsOrEmpty() ) {
				if ( memberId == null ) {
					// an Unassigned slot, which the chain skips
					uids.add(null);
					continue;
				}
				final TracingDatumFilterService member = filters.get(memberId);
				if ( member == null ) {
					warn(SimulationMessage.CODE_UNKNOWN_CHAIN_MEMBER,
							"The chain %s refers to the filter %s, which does not exist."
									.formatted(label(traced), SimulationMessage.quoted(memberId)),
							config.id(), null);
					continue;
				}
				final String uid = member.getUid();
				if ( uid == null || uid.isBlank() ) {
					warn(SimulationMessage.CODE_MISSING_UID,
							"The filter %s in the chain %s has no Service Name, so the chain cannot use it."
									.formatted(SimulationMessage.quoted(memberId), label(traced)),
							memberId, null);
					continue;
				}
				uids.add(uid);
			}
			chain.setTransformUids(uids.toArray(String[]::new));
		}
	}

	private void checkUids(Map<String, TracingDatumFilterService> filters) {
		final Map<String, List<String>> idsByUid = new LinkedHashMap<>(filters.size());
		for ( TracingDatumFilterService f : filters.values() ) {
			final String uid = f.getUid();
			if ( uid != null && !uid.isBlank() ) {
				idsByUid.computeIfAbsent(uid, _ -> new ArrayList<>(2)).add(f.filterId());
			}
		}
		for ( Map.Entry<String, List<String>> e : idsByUid.entrySet() ) {
			if ( e.getValue().size() > 1 ) {
				warn(SimulationMessage.CODE_DUPLICATE_UID,
						"The Service Name %s is used by %d filters (%s), so filter chains use only the first one."
								.formatted(SimulationMessage.quoted(e.getKey()), e.getValue().size(),
										SimulationMessage.quoted(e.getValue())),
						e.getValue().getFirst(), null);
			}
		}
	}

	private void checkChainCycles(Map<String, FilterConfig> configs,
			Map<String, TracingDatumFilterService> filters) {
		final Set<String> reported = new TreeSet<>();
		for ( FilterConfig config : configs.values() ) {
			TracingDatumFilterService f = filters.get(config.id());
			if ( f != null && f.isChain() ) {
				List<String> path = new ArrayList<>(4);
				path.add(config.id());
				findCycle(config.id(), path, configs, filters, reported);
			}
		}
	}

	private void findCycle(String chainId, List<String> path, Map<String, FilterConfig> configs,
			Map<String, TracingDatumFilterService> filters, Set<String> reported) {
		final FilterConfig config = configs.get(chainId);
		if ( config == null ) {
			return;
		}
		for ( String memberId : new LinkedHashSet<>(config.filterIdsOrEmpty()) ) {
			final TracingDatumFilterService member = (memberId != null ? filters.get(memberId) : null);
			if ( member == null || !member.isChain() ) {
				continue;
			}
			if ( path.contains(memberId) ) {
				List<String> cycle = new ArrayList<>(path.subList(path.indexOf(memberId), path.size()));
				cycle.add(memberId);
				if ( reported.add(memberId) ) {
					warn(SimulationMessage.CODE_CHAIN_CYCLE,
							"The chain %s contains itself (%s). SolarNode stops a chain when it calls itself."
									.formatted(label(member),
											String.join(" → ", cycle.stream()
													.map(SimulationMessage::quoted).toList())),
							memberId, null);
				}
				continue;
			}
			path.add(memberId);
			findCycle(memberId, path, configs, filters, reported);
			path.removeLast();
		}
	}

	private TracingDatumFilterService entryFilter(Map<String, TracingDatumFilterService> filters) {
		String id = request.entryPoint();
		if ( id == null || id.isBlank() ) {
			id = request.config().mainChainId();
		}
		if ( id == null || id.isBlank() ) {
			throw new SimulationException(
					"There is no entry point: configure the main filter chain, or choose an entry point.");
		}
		final TracingDatumFilterService entry = filters.get(id);
		if ( entry == null ) {
			throw new SimulationException(
					"The entry point filter %s does not exist.".formatted(SimulationMessage.quoted(id)));
		}
		return entry;
	}

	private void shutdown(Map<String, TracingDatumFilterService> filters) {
		for ( TracingDatumFilterService f : filters.values() ) {
			FilterTypeDefinition def = registry.definition(f.factoryUid());
			if ( def != null ) {
				lifecycle(def.destroy(), f.delegate(), f.filterId(), "stop");
			}
		}
	}

	private static String label(String filterId, Map<String, String> settings) {
		String uid = settings.get(UID_SETTING);
		return SimulationMessage.quoted(uid != null && !uid.isBlank() ? uid : filterId);
	}

	private static String label(TracingDatumFilterService filter) {
		String uid = filter.getUid();
		return SimulationMessage.quoted(uid != null && !uid.isBlank() ? uid : filter.filterId());
	}

	/* === Controls === */

	private void loadControls() {
		final SimulationEnvironment env = request.environment();
		final List<NodeControlConfig> configs = (env != null ? env.controls() : null);
		if ( configs == null ) {
			return;
		}
		final SimNodeControlService controls = context.controlService();
		for ( int i = 0; i < configs.size(); i++ ) {
			final NodeControlConfig config = configs.get(i);
			if ( config == null ) {
				continue;
			}
			final String controlId = (config.controlId() != null ? config.controlId().trim() : "");
			if ( controlId.isEmpty() ) {
				warn(SimulationMessage.CODE_INVALID_CONTROL,
						"Control %d has no Control ID, so it is left out.".formatted(i + 1), null, null);
				continue;
			}
			if ( context.hasControl(controlId) ) {
				warn(SimulationMessage.CODE_DUPLICATE_CONTROL_ID,
						"More than one control has the Control ID %s, so only the first one is used."
								.formatted(controlId),
						null, null);
				continue;
			}
			if ( config.kindValue() == ControlKind.OperationalModeSwitch ) {
				loadModeSwitch(controlId, config);
				continue;
			}
			final NodeControlPropertyType type = controlType(controlId, config.type());
			if ( !controls.addControl(controlId, type, config.value(), config.publishValue(),
					config.persistValue(), config.propertyName(),
					propertyType(controlId, config.propertyType())) ) {
				warn(SimulationMessage.CODE_INVALID_CONTROL,
						"The control %s starting value %s is not a valid %s value, so the control starts with no value."
								.formatted(controlId, SimulationMessage.quoted(config.value()),
										type.name()),
						null, null);
			}
		}
	}

	private void loadModeSwitch(String controlId, NodeControlConfig config) {
		final String mode = (config.mode() != null && !config.mode().isBlank() ? config.mode().trim()
				: null);
		if ( mode == null ) {
			warn(SimulationMessage.CODE_INVALID_CONTROL,
					"The Operational Mode Switch %s has no Operational Mode, so it handles no instructions, and its value is always false."
							.formatted(controlId),
					null, null);
		}
		context.addModeSwitch(controlId, mode, config.publishValue(), config.persistValue());
	}

	private NodeControlPropertyType controlType(String controlId, @Nullable String type) {
		if ( type == null || type.isBlank() ) {
			return NodeControlPropertyType.Boolean;
		}
		try {
			return NodeControlPropertyType.fromValue(type.trim());
		} catch ( IllegalArgumentException e ) {
			warn(SimulationMessage.CODE_INVALID_CONTROL,
					"The control %s has the Control Type %s, which is not one of Boolean, Float, Integer, Percent, or String, so it is a Boolean control, like a Mock control by default."
							.formatted(controlId, SimulationMessage.quoted(type)),
					null, null);
			return NodeControlPropertyType.Boolean;
		}
	}

	private @Nullable DatumSamplesType propertyType(String controlId, @Nullable String type) {
		if ( type == null || type.isBlank() ) {
			return null;
		}
		try {
			final DatumSamplesType result = DatumSamplesType.fromValue(type.trim());
			if ( result == DatumSamplesType.Instantaneous || result == DatumSamplesType.Accumulating
					|| result == DatumSamplesType.Status ) {
				return result;
			}
		} catch ( IllegalArgumentException e ) {
			// warn below
		}
		warn(SimulationMessage.CODE_INVALID_CONTROL,
				"The control %s has the Property Type %s, which is not one of Instantaneous, Accumulating, or Status, so its value is a Status property."
						.formatted(controlId, SimulationMessage.quoted(type)),
				null, null);
		return null;
	}

	/* === Datum sources === */

	/**
	 * Create the datum sources, with the clock at the start of the simulation,
	 * as a datum source might read the time when it is created.
	 */
	private void loadDatumSources() {
		final List<DatumSourceConfig> configs = request.datumSources();
		if ( configs == null ) {
			return;
		}
		for ( int i = 0; i < configs.size(); i++ ) {
			final DatumSourceConfig config = configs.get(i);
			if ( config == null ) {
				continue;
			}
			final String id = (config.id() != null && !config.id().isBlank() ? config.id()
					: "datumSource" + (i + 1));
			final Map<String, String> settings = new LinkedHashMap<>(config.settingsOrEmpty().size());
			for ( Map.Entry<String, String> e : config.settingsOrEmpty().entrySet() ) {
				if ( e.getKey() != null && e.getValue() != null ) {
					settings.put(e.getKey(), e.getValue());
				}
			}
			final DatumSourceTypeDefinition type = datumSourceTypes.definition(config.factoryUid());
			final String label = datumSourceLabel(i, type, settings);
			if ( datumSourceConfigs.putIfAbsent(id, config) != null ) {
				warnDatumSource("%s has the id %s, which another datum source has, so it is left out."
						.formatted(label, SimulationMessage.quoted(id)), null);
				continue;
			}
			if ( type == null ) {
				warnDatumSource(
						"%s has the type %s, which the simulator does not support, so it is not polled."
								.formatted(label, config.factoryUid()),
						id);
				continue;
			}
			final SimDatumSources.Source source = context.createDatumSource(type, id, label,
					new SplittableRandom(31L * type.factoryUid().hashCode() + i));
			polling = source;
			try {
				for ( SettingsApplier.Problem p : SettingsApplier.apply(source.job(), settings) ) {
					if ( p.unknown() ) {
						messages.add(SimulationMessage
								.warning(SimulationMessage.CODE_UNKNOWN_SETTING,
										"%s has no setting %s.".formatted(label, p.key()), null, null)
								.withDatumSourceId(id));
					} else {
						messages.add(SimulationMessage
								.warning(SimulationMessage.CODE_INVALID_SETTING,
										"%s setting %s could not be applied: %s".formatted(label,
												p.key(), p.message()),
										null, null)
								.withDatumSourceId(id));
					}
				}
				source.job().serviceDidStartup();
			} finally {
				polling = null;
			}
			final String limitation = type.limitations(settings);
			if ( limitation != null ) {
				messages.add(SimulationMessage
						.warning(SimulationMessage.CODE_FILTER_LIMITATION, limitation, null, null)
						.withDatumSourceId(id));
			}
			context.addDatumSource(source);
			// before the conductors are loaded, as their checks look for controls
			addDatumSourceControls(source);
		}
	}

	/**
	 * Get the label of a datum source in messages: its Service Name, or its
	 * position and type.
	 */
	private static String datumSourceLabel(int index, @Nullable DatumSourceTypeDefinition type,
			Map<String, String> settings) {
		final String uid = settings.get(DatumSourceTypeDefinition.SETTING_PREFIX + UID_SETTING);
		if ( uid != null && !uid.isBlank() ) {
			return "The datum source " + SimulationMessage.quoted(uid);
		}
		return (type != null
				? "Datum source %d (%s)".formatted(index + 1, type.name(Locale.getDefault()))
				: "Datum source %d".formatted(index + 1));
	}

	private void warnDatumSource(String message, @Nullable String datumSourceId) {
		messages.add(SimulationMessage
				.warning(SimulationMessage.CODE_INVALID_DATUM_SOURCE, message, null, null)
				.withDatumSourceId(datumSourceId));
	}

	/**
	 * Check the datum sources and schedule their polls, once the services they
	 * use are loaded.
	 *
	 * @param start
	 *        the start of the simulation
	 * @param runUntil
	 *        the time the simulation runs until, after the last input datum, or
	 *        {@code null}
	 * @param maxDatum
	 *        the maximum number of datum to process
	 * @return the number of polls by the end, up to {@code maxDatum} for each
	 *         datum source
	 */
	private int startDatumSources(Instant start, @Nullable Instant runUntil, int maxDatum) {
		final SimTaskScheduler scheduler = context.taskScheduler();
		int total = 0;
		Instant end = runUntil;
		if ( end == null && !input.isEmpty() ) {
			end = timestamp(input.getLast());
		}
		for ( SimDatumSources.Source source : context.datumSources().sources() ) {
			final String label = source.label();
			final String sourceId = source.type().sourceId().apply(source.datumSource());
			if ( sourceId == null || sourceId.isBlank() ) {
				warnDatumSource(
						"%s has no Source ID, so the Datum Queue discards its datum.".formatted(label),
						source.id());
			}
			polling = source;
			try {
				final String problem = source.type().check(source.datumSource(), context);
				if ( problem != null ) {
					warnDatumSource(problem, source.id());
				}
			} finally {
				polling = null;
			}

			final Trigger trigger = trigger(source);
			source.setTrigger(trigger);
			if ( trigger == null ) {
				continue;
			}
			if ( end != null ) {
				final int polls = pollCount(trigger, start, end, maxDatum + 1);
				total += Math.min(polls, maxDatum);
				if ( polls > maxDatum ) {
					warnDatumSource(
							"%s is polled more than %d times by %s, the maximum number of datum allowed, so the simulation stops before then."
									.formatted(label, maxDatum, end),
							source.id());
				}
			}
			// a background task, as polls never end
			scheduler.background(() -> {
				var _ = scheduler.schedule(() -> poll(source), trigger);
			});
		}
		return total;
	}

	/**
	 * Get the poll trigger of a datum source, like the node's job scheduler.
	 *
	 * @return the trigger, or {@code null} if the datum source is not polled
	 */
	private @Nullable Trigger trigger(SimDatumSources.Source source) {
		final String schedule = source.job().getSchedule();
		final String label = source.label();
		if ( schedule == null || schedule.isBlank() ) {
			warnDatumSource("%s has no Schedule, so it is never polled.".formatted(label), source.id());
			return null;
		}
		final String expr = schedule.trim();
		try {
			final long ms = Long.parseLong(expr);
			if ( ms < 0 ) {
				warnDatumSource(
						"%s has the Schedule %s, which is not a valid period or cron expression, so it is never polled."
								.formatted(label, SimulationMessage.quoted(expr)),
						source.id());
				return null;
			}
			if ( ms == 0 ) {
				warnDatumSource(
						"%s has the Schedule 0, which a node runs without a pause, so the simulator does not poll it."
								.formatted(label),
						source.id());
				return null;
			}
		} catch ( NumberFormatException e ) {
			if ( !CronExpression.isValidExpression(expr) ) {
				warnDatumSource(
						"%s has the Schedule %s, which is not a valid period or cron expression, so it is never polled."
								.formatted(label, SimulationMessage.quoted(expr)),
						source.id());
				return null;
			}
		}
		// cron seconds are not randomized, so runs repeat
		return JobUtils.triggerForExpression(expr, TimeUnit.MILLISECONDS, false);
	}

	/**
	 * Count the polls of a trigger from a start time to an end time, up to a
	 * limit.
	 */
	private static int pollCount(Trigger trigger, Instant start, Instant end, int limit) {
		final SimpleTriggerContext ctx = new SimpleTriggerContext(
				Clock.fixed(start, ZoneId.systemDefault()));
		int count = 0;
		while ( count < limit ) {
			final Instant next = trigger.nextExecution(ctx);
			if ( next == null || next.isAfter(end) ) {
				break;
			}
			count++;
			ctx.update(next, next, next);
		}
		return count;
	}

	/**
	 * Get the time of a datum source's first poll.
	 */
	private static @Nullable Instant firstPoll(Trigger trigger, Instant start) {
		return trigger
				.nextExecution(new SimpleTriggerContext(Clock.fixed(start, ZoneId.systemDefault())));
	}

	/**
	 * Add the controls of a datum source that is a control provider, unless
	 * another control has the same ID.
	 */
	private void addDatumSourceControls(SimDatumSources.Source source) {
		if ( !(source.datumSource() instanceof NodeControlProvider provider) ) {
			return;
		}
		final DatumSourceConfig config = datumSourceConfigs.get(source.id());
		for ( String controlId : provider.getAvailableControlIds() ) {
			if ( controlId == null || controlId.isBlank() ) {
				continue;
			}
			if ( context.hasControl(controlId) ) {
				warnDatumSource(
						"%s has the control ID %s, which another control also has. That control is asked first, so it handles the control's instructions."
								.formatted(source.label(), controlId),
						source.id());
				continue;
			}
			context.datumSources().addControl(source, controlId, readsPrevious(source, controlId),
					config != null ? config.publishValue() : ControlPublishMode.Change,
					config != null && config.persistValue());
		}
	}

	/**
	 * Test if a datum source control's value can be read before a set without
	 * side effects: only a set changes the Mock Battery's target power rate,
	 * but its state of charge changes over time.
	 */
	private boolean readsPrevious(SimDatumSources.Source source, String controlId) {
		return (source.datumSource() instanceof MockBatteryDatumDataSource b && controlId.equals(context
				.placeholderService().resolvePlaceholders(b.getTargetPowerRateControlId(), null)));
	}

	/**
	 * Poll a datum source, like the node's job scheduler running its job. The
	 * datum it offers are processed next, before datum due after them.
	 */
	private void poll(SimDatumSources.Source source) {
		final int before = polledCount;
		polling = source;
		try {
			source.job().getJobService().executeJobService();
		} catch ( Exception e ) {
			// the poll job logs its own errors
			warnDatumSource("Error polling %s: %s"
					.formatted(source.label().replaceFirst("^The ", "the "), e.toString()), source.id());
		} finally {
			polling = null;
		}
		source.polled(context.simulatedClock().instant(), polledCount - before);
	}

	private void addDatumSourceMessages(Instant start) {
		for ( SimDatumSources.Source source : context.datumSources().sources() ) {
			final Trigger trigger = source.trigger();
			if ( trigger == null ) {
				continue;
			}
			if ( source.polls() == 0 && !invokedSources.contains(source.id()) ) {
				final Instant first = firstPoll(trigger, start);
				messages.add(SimulationMessage
						.info(SimulationMessage.CODE_DATUM_SOURCE_POLLS, first != null
								? "%s was not polled, as the simulation ended before its first poll, due at %s. Keep running after the last datum to poll it."
										.formatted(source.label(), first)
								: "%s was not polled, as its schedule has no time after the simulation started."
										.formatted(source.label()),
								null)
						.withDatumSourceId(source.id()));
			} else if ( source.emptyPolls() > 0 ) {
				messages.add(SimulationMessage.info(SimulationMessage.CODE_DATUM_SOURCE_POLLS,
						(source.emptyPolls() == source.polls()
								? "%1$s returned no datum from any of its %3$d polls."
								: "%1$s returned no datum from %2$d of its %3$d polls, the first at %4$s.")
										.formatted(source.label(), source.emptyPolls(), source.polls(),
												source.firstEmptyPoll()),
						null).withDatumSourceId(source.id()));
			}
		}
	}

	private void shutdownDatumSources() {
		for ( SimDatumSources.Source source : context.datumSources().sources() ) {
			polling = source;
			try {
				source.job().serviceDidShutdown();
			} finally {
				polling = null;
			}
		}
	}

	/* === Operational Mode Datum Source Invokers === */

	/**
	 * Create the Operational Mode Datum Source Invokers, after the datum
	 * sources, as their checks look for datum sources.
	 */
	private void loadInvokers() {
		final List<OpModeInvokerConfig> configs = request.invokers();
		if ( configs == null ) {
			return;
		}
		final Set<String> ids = new HashSet<>(configs.size());
		for ( int i = 0; i < configs.size(); i++ ) {
			final OpModeInvokerConfig config = configs.get(i);
			if ( config == null ) {
				continue;
			}
			final String id = (config.id() != null && !config.id().isBlank() ? config.id()
					: "invoker" + (i + 1));
			final Map<String, String> settings = new LinkedHashMap<>(config.settingsOrEmpty().size());
			for ( Map.Entry<String, String> e : config.settingsOrEmpty().entrySet() ) {
				if ( e.getKey() != null && e.getValue() != null ) {
					settings.put(e.getKey(), e.getValue());
				}
			}
			final String label = invokerLabel(i, settings);
			if ( datumSourceConfigs.containsKey(id) || !ids.add(id) ) {
				warnInvoker(
						"%s has the id %s, which another invoker or datum source has, so it is left out."
								.formatted(label, SimulationMessage.quoted(id)),
						null);
				continue;
			}
			final SimOpModeInvokers.Invoker invoker = context.createInvoker(id, label);
			invoking = invoker;
			try {
				for ( SettingsApplier.Problem p : SettingsApplier.apply(invoker.invoker(), settings) ) {
					messages.add(SimulationMessage
							.warning(
									p.unknown() ? SimulationMessage.CODE_UNKNOWN_SETTING
											: SimulationMessage.CODE_INVALID_SETTING,
									p.unknown() ? "%s has no setting %s.".formatted(label, p.key())
											: "%s setting %s could not be applied: %s".formatted(label,
													p.key(), p.message()),
									null, null)
							.withDatumSourceId(id));
				}
			} finally {
				invoking = null;
			}
			checkInvoker(invoker);
		}
	}

	/**
	 * Get the label of an invoker in messages: its Service Name, or its
	 * position.
	 */
	private static String invokerLabel(int index, Map<String, String> settings) {
		final String uid = settings.get(UID_SETTING);
		if ( uid != null && !uid.isBlank() ) {
			return "The Operational Mode Invoker " + SimulationMessage.quoted(uid);
		}
		return "Operational Mode Invoker %d".formatted(index + 1);
	}

	private void warnInvoker(String message, @Nullable String invokerId) {
		messages.add(
				SimulationMessage.warning(SimulationMessage.CODE_INVALID_INVOKER, message, null, null)
						.withDatumSourceId(invokerId));
	}

	/**
	 * Check an invoker's configuration for what makes it poll nothing.
	 */
	private void checkInvoker(SimOpModeInvokers.Invoker invoker) {
		final DatumDataSourceOpModeInvoker inv = invoker.invoker();
		final String label = invoker.label();
		final String mode = inv.getOperationalMode();
		if ( mode == null || mode.isBlank() ) {
			warnInvoker("%s has no Mode, so it never polls.".formatted(label), invoker.id());
		}
		final DatumDataSourceScheduleConfig[] configs = inv.getConfigurations();
		if ( configs == null || configs.length == 0 ) {
			warnInvoker("%s has no Data Source Configurations, so it never polls.".formatted(label),
					invoker.id());
			return;
		}
		for ( int i = 0; i < configs.length; i++ ) {
			final DatumDataSourceScheduleConfig config = configs[i];
			if ( config == null ) {
				continue;
			}
			final String item = "%s Data Source Configuration %d".formatted(label, i + 1);
			final String schedule = config.getSchedule();
			if ( schedule == null || schedule.isBlank() ) {
				warnInvoker("%s has no Schedule, so it never polls.".formatted(item), invoker.id());
				continue;
			}
			final String expr = schedule.trim();
			try {
				final long seconds = Long.parseLong(expr);
				if ( seconds < 0 ) {
					warnInvoker(
							"%s has the Schedule %s, which is not a valid period or cron expression, so it never polls."
									.formatted(item, SimulationMessage.quoted(expr)),
							invoker.id());
					continue;
				}
				if ( seconds == 0 ) {
					warnInvoker(
							"%s has the Schedule 0, which a node runs without a pause, so the simulator does not run it."
									.formatted(item),
							invoker.id());
					continue;
				}
			} catch ( NumberFormatException e ) {
				if ( !CronExpression.isValidExpression(expr) ) {
					warnInvoker(
							"%s has the Schedule %s, which is not a valid period or cron expression, so it never polls."
									.formatted(item, SimulationMessage.quoted(expr)),
							invoker.id());
					continue;
				}
			}
			if ( context.datumSources().sources().stream()
					.noneMatch(s -> config.matches(s.datumSource())) ) {
				warnInvoker("%s matches no datum source, so it polls nothing.".formatted(item),
						invoker.id());
			}
		}
	}

	/**
	 * Start the invokers, once the operational modes are loaded: each one
	 * starts polling if its mode is active.
	 */
	private void startInvokers() {
		for ( SimOpModeInvokers.Invoker invoker : context.invokers().invokers() ) {
			context.invokers().start(invoker, context.eventAdmin());
		}
	}

	private void addInvokerMessages() {
		for ( SimOpModeInvokers.Invoker invoker : context.invokers().invokers() ) {
			final String mode = invoker.invoker().getOperationalMode();
			if ( mode == null || mode.isBlank() || invoker.runs() > 0 ) {
				continue;
			}
			messages.add(SimulationMessage.info(SimulationMessage.CODE_INVOKER_RUNS, invoker.active()
					? "%s did not poll, as the simulation ended before its first poll while its mode was active."
							.formatted(invoker.label())
					: "%s did not poll, as its mode %s was never active.".formatted(invoker.label(),
							SimulationMessage.quoted(mode.trim())),
					null).withDatumSourceId(invoker.id()));
		}
	}

	/* === Control Conductors === */

	private void loadConductors() {
		final SimulationEnvironment env = request.environment();
		final List<ControlConductorConfig> configs = (env != null ? env.conductors() : null);
		if ( configs == null ) {
			return;
		}
		final Set<String> uids = new HashSet<>(configs.size());
		for ( int i = 0; i < configs.size(); i++ ) {
			final ControlConductorConfig config = configs.get(i);
			if ( config == null ) {
				continue;
			}
			final Map<String, String> settings = new LinkedHashMap<>(config.settingsOrEmpty().size());
			for ( Map.Entry<String, String> e : config.settingsOrEmpty().entrySet() ) {
				if ( e.getKey() != null && e.getValue() != null ) {
					settings.put(e.getKey(), e.getValue());
				}
			}
			final ControlConductor conductor = context.createConductor();
			final String uid = settings.get(UID_SETTING);
			final String label = (uid != null && !uid.isBlank() ? SimulationMessage.quoted(uid)
					: String.valueOf(i + 1));
			for ( SettingsApplier.Problem p : SettingsApplier.apply(conductor, settings) ) {
				if ( p.unknown() ) {
					warn(SimulationMessage.CODE_UNKNOWN_SETTING,
							"The Control Conductor %s has no setting %s.".formatted(label, p.key()),
							null, null);
				} else {
					warn(SimulationMessage.CODE_INVALID_SETTING,
							"The Control Conductor %s setting %s could not be applied: %s"
									.formatted(label, p.key(), p.message()),
							null, null);
				}
			}
			if ( uid == null || uid.isBlank() ) {
				warn(SimulationMessage.CODE_INVALID_CONDUCTOR,
						"Control Conductor %s has no Service Name, so it handles no instructions."
								.formatted(label),
						null, null);
			} else if ( !uids.add(uid.toLowerCase(Locale.ROOT)) ) {
				warn(SimulationMessage.CODE_INVALID_CONDUCTOR,
						"More than one Control Conductor has the Service Name %s, so only the first one handles its instructions."
								.formatted(label),
						null, null);
			}
			checkConductor(conductor, label);
			context.addConductor(conductor);
		}
	}

	/**
	 * Report the problems of a Control Conductor's configuration, which
	 * SolarNode only reports when it declines an instruction.
	 */
	private void checkConductor(ControlConductor conductor, String label) {
		final ExpressionConfig predicate = conductor.getPredicate();
		if ( predicate != null ) {
			checkExpression(predicate.getExpression(), predicate.getExpressionServiceId(),
					"The Control Conductor %s Predicate Expression".formatted(label));
		}
		final ControlTaskConfig[] tasks = conductor.getTaskConfigs();
		if ( tasks == null || tasks.length < 1 ) {
			warn(SimulationMessage.CODE_INVALID_CONDUCTOR,
					"The Control Conductor %s has no tasks, so it declines every OrchestrateControls instruction."
							.formatted(label),
					null, null);
			return;
		}
		for ( int i = 0; i < tasks.length; i++ ) {
			final ControlTaskConfig task = tasks[i];
			final String taskLabel = "Task %d of the Control Conductor %s".formatted(i + 1, label);
			if ( task == null || !task.isValid() ) {
				warn(SimulationMessage.CODE_INVALID_CONDUCTOR,
						"%s has no Control or no Value, so the conductor declines every OrchestrateControls instruction."
								.formatted(taskLabel),
						null, null);
				continue;
			}
			final String offset = task.getExecutionOffset();
			if ( offset == null || offset.isBlank() ) {
				warn(SimulationMessage.CODE_INVALID_CONDUCTOR,
						"%s has no Offset, so the conductor declines every OrchestrateControls instruction."
								.formatted(taskLabel),
						null, null);
			} else if ( !offset.contains("{")
					&& task.executionTime(Instant.EPOCH, null, Map.of()) == null ) {
				warn(SimulationMessage.CODE_INVALID_CONDUCTOR,
						"%s has the Offset %s, which is neither a number of milliseconds nor an ISO 8601 duration like PT1H, so the conductor declines every OrchestrateControls instruction."
								.formatted(taskLabel, SimulationMessage.quoted(offset)),
						null, null);
			}
			final String controlId = task.getControlId();
			if ( controlId != null && !controlId.contains("{")
					&& !context.hasControl(controlId.trim()) ) {
				warn(SimulationMessage.CODE_INVALID_CONDUCTOR,
						"%s sets the control %s, which is not one of the simulation's Controls, so the task is declined, as on a node without that control."
								.formatted(taskLabel, controlId.trim()),
						null, null);
			}
			checkExpression(task.getValue(), task.getExpressionServiceId(),
					"%s Value".formatted(taskLabel));
		}
	}

	private void checkExpression(@Nullable String expression, @Nullable String serviceId, String label) {
		if ( expression == null || expression.isBlank() || serviceId == null || serviceId.isBlank()
				|| !serviceId.equalsIgnoreCase(expressionService.getUid()) ) {
			return;
		}
		try {
			expressionService.parseExpression(expression);
		} catch ( RuntimeException e ) {
			warn(SimulationMessage.CODE_INVALID_CONDUCTOR, "%s is not a valid expression: %s".formatted(
					label, e.getMessage() != null ? e.getMessage() : e.toString()), null, null);
		}
	}

	/* === Metadata === */

	private GeneralDatumMetadata nodeMetadata() {
		final SimulationEnvironment env = request.environment();
		final JsonNode json = (env != null ? env.nodeMetadata() : null);
		final String label = "Node metadata";
		final GeneralDatumMetadata meta = DatumJson.metadata(json, label);
		ignoredMetadata(json, label);
		return meta;
	}

	private void registerUserMetadataServices() {
		final SimulationEnvironment env = request.environment();
		final List<UserMetadataConfig> configs = (env != null ? env.userMetadata() : null);
		if ( configs == null ) {
			return;
		}
		final Set<String> uids = new HashSet<>(4);
		uids.add(SimMetadataService.NODE_METADATA_SERVICE_UID);
		for ( int i = 0; i < configs.size(); i++ ) {
			final UserMetadataConfig config = configs.get(i);
			if ( config == null ) {
				continue;
			}
			final String name = config.uid();
			final String uid = (name != null && !name.isBlank() ? name : null);
			final String label = "User metadata "
					+ (uid != null ? "\"" + uid + "\"" : String.valueOf(i + 1));
			final GeneralDatumMetadata meta = DatumJson.metadata(config.metadata(), label);
			ignoredMetadata(config.metadata(), label);
			if ( uid == null ) {
				warn(SimulationMessage.CODE_MISSING_UID,
						"User Metadata Service %d has no Service Name, so filters cannot find it."
								.formatted(i + 1),
						null, null);
			} else if ( !uids.add(uid) ) {
				warn(SimulationMessage.CODE_DUPLICATE_UID,
						"More than one metadata service has the Service Name %s, so filters use the first one."
								.formatted(SimulationMessage.quoted(uid)),
						null, null);
			}
			context.registerMetadataService(new SimMetadataService(uid, meta));
		}
	}

	private void loadNodeLocation() {
		final SimulationEnvironment env = request.environment();
		final NodeLocation loc = (env != null ? env.nodeLocation() : null);
		if ( loc == null ) {
			return;
		}
		final SimpleLocation location = new SimpleLocation();
		location.setLatitude(loc.latitude());
		location.setLongitude(loc.longitude());
		location.setElevation(loc.elevation());
		context.locationService().updateNodeLocation(location);
	}

	private void loadPlaceholders() {
		final SimulationEnvironment env = request.environment();
		final Map<String, String> placeholders = (env != null ? env.placeholders() : null);
		if ( placeholders == null ) {
			return;
		}
		for ( Map.Entry<String, String> e : placeholders.entrySet() ) {
			final String name = e.getKey();
			if ( name != null && !name.isBlank() && e.getValue() != null ) {
				// where the node keeps placeholders that SolarNetwork registers
				context.settingDao().storeSetting(SettingsPlaceholderService.SETTING_KEY, name.trim(),
						e.getValue());
			}
		}
	}

	private void loadDatumMetadata() {
		final SimulationEnvironment env = request.environment();
		final Map<String, JsonNode> metadata = (env != null ? env.datumMetadata() : null);
		if ( metadata == null ) {
			return;
		}
		for ( Map.Entry<String, JsonNode> e : metadata.entrySet() ) {
			final String sourceId = e.getKey();
			if ( sourceId == null || sourceId.isBlank() ) {
				continue;
			}
			final String label = "Datum metadata %s".formatted(sourceId);
			final GeneralDatumMetadata meta = DatumJson.metadata(e.getValue(), label);
			ignoredMetadata(e.getValue(), label);
			context.simulatedDatumMetadataService().addSourceMetadata(sourceId, meta);
		}
	}

	private void ignoredMetadata(@Nullable JsonNode json, String label) {
		final List<String> ignored = DatumJson.ignoredMetadataProperties(json);
		if ( ignored.isEmpty() ) {
			return;
		}
		warn(SimulationMessage.CODE_IGNORED_METADATA,
				"%s has the %s %s, which SolarNode ignores. Metadata has only m, pm, and t properties."
						.formatted(label, ignored.size() == 1 ? "property" : "properties",
								String.join(", ", ignored)),
				null, null);
	}

	/* === Starting state === */

	private void loadEnvironment(Instant defaultDate) {
		final SimulationEnvironment env = request.environment();
		if ( env == null ) {
			return;
		}
		if ( env.opModes() != null ) {
			for ( String mode : env.opModes() ) {
				if ( mode == null ) {
					continue;
				}
				// SolarNode lowercases mode names
				String m = mode.trim().toLowerCase(Locale.ROOT);
				if ( !m.isEmpty() ) {
					context.opModes().put(m, DefaultOperationalModesService.NO_EXPIRATION);
					// where the service reads the active modes when it starts
					context.settingDao().storeSetting(DefaultOperationalModesService.SETTING_OP_MODE, m,
							m);
				}
			}
		}
		final String csv = env.localStateCsv();
		if ( csv != null && !csv.isBlank() ) {
			try {
				localStateService().importCsvConfiguration(
						new StringReader(LocalStateCsv.normalize(csv, defaultDate)), true);
			} catch ( IOException e ) {
				throw new SimulationException("Error loading the Local State CSV: " + e.getMessage(), e);
			}
		}
	}

	private DefaultLocalStateService localStateService() {
		return new DefaultLocalStateService(new StaticOptionalService<>(context.localStateDao()));
	}

	private void loadHistory(Instant firstDate) {
		final SimulationHistory history = request.history();
		if ( history == null ) {
			return;
		}
		final List<NodeDatum> filtered = parseHistory(history.filtered(), "History");
		final List<NodeDatum> unfiltered = (history.unfiltered() != null
				? parseHistory(history.unfiltered(), "Unfiltered History")
				: filtered);
		addHistory(filtered, Stage.PostFilter, "History", firstDate);
		addHistory(unfiltered, Stage.PreFilter, "Unfiltered History", firstDate);
	}

	private static List<NodeDatum> parseHistory(@Nullable List<JsonNode> nodes, String label) {
		if ( nodes == null || nodes.isEmpty() ) {
			return List.of();
		}
		final List<NodeDatum> result = new ArrayList<>(nodes.size());
		for ( int i = 0; i < nodes.size(); i++ ) {
			final String datumLabel = label + " datum " + (i + 1);
			final Datum d = DatumJson.parse(nodes.get(i), datumLabel);
			final Instant ts = d.getTimestamp();
			if ( ts == null ) {
				throw new SimulationException(datumLabel + " has no created date.");
			}
			result.add(DatumJson.nodeDatum(d, ts));
		}
		result.sort(Comparator.comparing(SimulationRun::timestamp));
		return result;
	}

	private void addHistory(List<NodeDatum> datum, Stage stage, String label, Instant firstDate) {
		if ( datum.isEmpty() ) {
			return;
		}
		final Map<String, Integer> counts = new TreeMap<>();
		boolean afterInput = false;
		for ( NodeDatum d : datum ) {
			context.datumService().datumQueueWillProcess(context.simulatedDatumQueue(), d, stage, true);
			counts.merge(String.valueOf(d.getSourceId()), 1, Integer::sum);
			if ( timestamp(d).isAfter(firstDate) ) {
				afterInput = true;
			}
		}
		final int max = DatumHistory.DEFAULT_CONFIG.getRawCount();
		for ( Map.Entry<String, Integer> e : counts.entrySet() ) {
			if ( e.getValue() > max ) {
				warn(SimulationMessage.CODE_HISTORY_OVERFLOW,
						"%s has %d datum for the source %s, but SolarNode keeps only the latest %d, so the oldest %d were dropped."
								.formatted(label, e.getValue(), e.getKey(), max, e.getValue() - max),
						null, null);
			}
		}
		if ( afterInput ) {
			warn(SimulationMessage.CODE_HISTORY_AFTER_INPUT,
					"%s has datum newer than the first input datum.".formatted(label), null, null);
		}
	}

	private static Instant timestamp(NodeDatum datum) {
		Instant ts = datum.getTimestamp();
		return (ts != null ? ts : Instant.EPOCH);
	}

	/* === Datum Queue === */

	private QueuedDatum enqueue(NodeDatum datum, boolean persist, int depth, DatumOrigin origin) {
		final QueuedDatum qd = new QueuedDatum(nextSeq++, datum, persist, depth, origin);
		queue.addLast(qd);
		addLineage(qd);
		return qd;
	}

	// the reference comparison of the filter result is how DefaultDatumQueue detects no change
	@SuppressWarnings("ReferenceEquality")
	private void process(QueuedDatum qd, DatumFilterService entry) {
		this.current = qd;
		final Instant ts = qd.datum().getTimestamp();
		if ( ts != null ) {
			context.simulatedClock().setInstant(ts);
		}
		final DatumRecord record = new DatumRecord(qd.seq(), qd.origin(), DatumInfo.from(qd.datum()),
				qd.persist());
		if ( options.detailValue() == SimulationOptions.Detail.Debug ) {
			record.startState = snapshot(false, queue.stream().map(QueuedDatum::seq).toList());
		}
		processedCount++;
		lastSeq = qd.seq();

		// like DefaultDatumQueue: unfiltered history, filters, then filtered history
		context.datumService().datumQueueWillProcess(context.simulatedDatumQueue(), qd.datum(),
				Stage.PreFilter, qd.persist());
		tracer.beginDatum(record);
		NodeDatum result;
		try {
			final DatumSamplesOperations in = qd.datum().asSampleOperations();
			final DatumSamplesOperations out = entry.filter(qd.datum(), in, new HashMap<>(4));
			if ( out == null ) {
				result = null;
			} else if ( out == in ) {
				result = qd.datum();
			} else if ( out instanceof NodeDatum nd ) {
				result = nd;
			} else {
				result = qd.datum().copyWithSamples(out);
			}
		} catch ( RuntimeException | StackOverflowError e ) {
			// DefaultDatumQueue continues with the original datum by default
			record.error = (e.getMessage() != null ? e.getMessage() : e.toString());
			result = qd.datum();
		} finally {
			tracer.endDatum();
		}
		if ( result != null ) {
			context.datumService().datumQueueWillProcess(context.simulatedDatumQueue(), result,
					Stage.PostFilter, qd.persist());
			record.output = DatumInfo.from(result);
			outputCount++;
		} else {
			discardedCount++;
		}
		this.current = null;
		observer.processed(record.toTrace());
	}

	@Override
	public boolean offer(NodeDatum datum, boolean persist) {
		return offer(datum, persist, null);
	}

	/**
	 * Accept a datum a filter or control offers to the Datum Queue.
	 *
	 * @param datum
	 *        the datum
	 * @param persist
	 *        {@code true} to persist the datum after filtering
	 * @param controlId
	 *        the ID of the control that published the datum, or {@code null}
	 * @return {@code true} if the datum was accepted
	 */
	private boolean offer(NodeDatum datum, boolean persist, @Nullable String controlId) {
		final QueuedDatum parent = this.current;
		final StepRecord step = tracer.currentStep();
		final int depth = (parent != null ? parent.depth() + 1 : 0);
		final String filterId = (step != null ? step.filterId : null);
		if ( depth > options.maxGenerationDepthValue() ) {
			depthLimitReached(datum, parent, filterId);
			return false;
		}
		final RunRecord run = tracer.currentRun();
		if ( run != null && step == null ) {
			// a stored instruction the job ran: processed after the job, before later datum
			final SimInstructionService.Issuer issuer = run.issuer;
			final QueuedDatum qd = new QueuedDatum(nextSeq++, datum, persist, 0, DatumOrigin
					.instruction(run.instruction.number(), issuer != null ? issuer.filterId() : null)
					.withControlId(controlId));
			addLineage(qd);
			taskGenerated.add(qd);
			run.generated.add(qd.seq());
			generatedCount++;
			return true;
		}
		final SimDatumSources.Source datumSource = polling;
		if ( datumSource != null && step == null ) {
			// a poll, or an invoker's read: processed after it, before later datum
			final SimOpModeInvokers.Invoker invoker = invoking;
			if ( invoker != null ) {
				invokedSources.add(datumSource.id());
			}
			final QueuedDatum qd = new QueuedDatum(nextSeq++, datum, persist, 0,
					DatumOrigin.polled(datumSource.id()).withControlId(controlId)
							.withInvokerId(invoker != null ? invoker.id() : null));
			addLineage(qd);
			taskGenerated.add(qd);
			polledCount++;
			return true;
		}
		final SimTaskScheduler.Task task = firing;
		if ( task != null && step == null ) {
			// a scheduled task: processed after the task, before later datum
			final QueuedDatum qd = new QueuedDatum(nextSeq++, datum, persist, 0,
					DatumOrigin.scheduled(task.owner()).withControlId(controlId));
			addLineage(qd);
			taskGenerated.add(qd);
			generatedCount++;
			return true;
		}
		final QueuedDatum qd = enqueue(datum, persist, depth, DatumOrigin
				.generated(parent != null ? parent.seq() : null, step != null ? step.id : null, filterId)
				.withControlId(controlId));
		generatedCount++;
		if ( step != null ) {
			step.generated.add(qd.seq());
		}
		return true;
	}

	private void addLineage(QueuedDatum qd) {
		lineage.put(qd.seq(), new Lineage(qd.datum().getSourceId(), qd.origin().parentSeq()));
	}

	private void depthLimitReached(NodeDatum datum, @Nullable QueuedDatum parent,
			@Nullable String filterId) {
		final String key = (filterId != null ? filterId : "");
		final DepthLimit existing = depthLimits.get(key);
		if ( existing != null ) {
			depthLimits.put(key, new DepthLimit(existing.filterId(), existing.datumSeq(),
					existing.sourceId(), existing.path(), existing.count() + 1));
			return;
		}
		final List<String> path = new ArrayList<>(8);
		path.add(String.valueOf(datum.getSourceId()));
		Lineage p = (parent != null ? lineage.get(parent.seq()) : null);
		while ( p != null ) {
			path.addFirst(String.valueOf(p.sourceId()));
			Integer parentSeq = p.parentSeq();
			p = (parentSeq != null ? lineage.get(parentSeq) : null);
		}
		depthLimits.put(key, new DepthLimit(filterId, parent != null ? parent.seq() : null,
				String.valueOf(datum.getSourceId()), String.join(" → ", path), 1));
	}

	private void addDepthLimitMessages() {
		for ( DepthLimit limit : depthLimits.values() ) {
			warn(SimulationMessage.CODE_GENERATION_DEPTH_LIMIT,
					"Stopped a possible infinite loop: %d datum generated by the filter %s (first for the source %s) were discarded, because they exceeded the generation depth limit of %d. Generation path: %s"
							.formatted(limit.count(), SimulationMessage.quoted(limit.filterId()),
									limit.sourceId(), options.maxGenerationDepthValue(), limit.path()),
					limit.filterId(), limit.datumSeq());
		}
	}

	/* === State === */

	private SimulationState snapshot(boolean includeCsv, @Nullable List<Integer> queued) {
		final List<String> modes = new ArrayList<>(
				new TreeSet<>(context.opModesService().activeOperationalModes()));
		final Collection<LocalState> states = context.localStateDao().getAll(null);
		final List<LocalStateInfo> localState = new ArrayList<>(states.size());
		for ( LocalState s : states ) {
			localState.add(new LocalStateInfo(s.getKey(), s.getType().name(), s.getValue(),
					s.getCreated(), s.getModified()));
		}
		final Collection<Metric> savedMetrics = context.metricDao().getAll(null);
		final List<MetricInfo> metrics = new ArrayList<>(savedMetrics.size());
		for ( Metric m : savedMetrics ) {
			metrics.add(metricInfo(m));
		}
		// the final state reads the datum source controls' values
		return new SimulationState(modes, localState, metrics, context.controlInfos(includeCsv),
				context.instructionService().waiting(), includeCsv ? localStateCsv() : null,
				history(context.datumService()), history(context.datumService().unfiltered()), queued);
	}

	private static MetricInfo metricInfo(Metric m) {
		return new MetricInfo(m.getTimestamp(), m.getType(), m.getName(), m.getValue());
	}

	private String localStateCsv() {
		final StringWriter out = new StringWriter(256);
		try {
			localStateService().exportCsvConfiguration(out);
		} catch ( IOException e ) {
			throw new UncheckedIOException(e);
		}
		return out.toString();
	}

	private static Map<String, List<DatumInfo>> history(DatumHistorian historian) {
		final Map<String, List<DatumInfo>> result = new TreeMap<>();
		for ( int offset = 0; offset < MAX_HISTORY_OFFSET; offset++ ) {
			final Collection<NodeDatum> datum = historian.offset((Set<String>) null, offset,
					NodeDatum.class);
			if ( datum == null || datum.isEmpty() ) {
				break;
			}
			for ( NodeDatum d : datum ) {
				result.computeIfAbsent(String.valueOf(d.getSourceId()), _ -> new ArrayList<>(4))
						.add(DatumInfo.from(d));
			}
		}
		return result;
	}

	private void warn(String code, String message, @Nullable String filterId,
			@Nullable Integer datumSeq) {
		messages.add(SimulationMessage.warning(code, message, filterId, datumSeq));
	}

}
