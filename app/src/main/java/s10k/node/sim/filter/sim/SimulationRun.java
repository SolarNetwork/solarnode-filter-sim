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
import java.time.Duration;
import java.time.Instant;
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
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.SimpleLocation;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.GeneralDatumMetadata;
import net.solarnetwork.node.domain.LocalState;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.metrics.domain.Metric;
import net.solarnetwork.node.runtime.DatumHistory;
import net.solarnetwork.node.runtime.DefaultLocalStateService;
import net.solarnetwork.node.runtime.DefaultOperationalModesService;
import net.solarnetwork.node.service.DatumHistorian;
import net.solarnetwork.node.service.DatumQueueProcessObserver.Stage;
import net.solarnetwork.node.service.support.DatumFilterChainService;
import net.solarnetwork.node.service.support.SettingsPlaceholderService;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ExpressionService;
import net.solarnetwork.service.StaticOptionalService;
import s10k.node.sim.filter.domain.DatumInfo;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.LocalStateInfo;
import s10k.node.sim.filter.domain.MetricInfo;
import s10k.node.sim.filter.domain.NodeLocation;
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
import s10k.node.sim.filter.registry.FilterTypeDefinition;
import s10k.node.sim.filter.registry.FilterTypeRegistry;
import s10k.node.sim.filter.settings.SettingsApplier;
import s10k.node.sim.filter.sim.FilterTracer.DatumRecord;
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
 * @version 1.0
 */
final class SimulationRun implements SimulatedDatumQueue.Sink {

	/** The {@code uid} setting key. */
	private static final String UID_SETTING = "uid";

	/** The chain member setting keys, which are generated from the config. */
	private static final String TRANSFORM_UIDS_SETTING = "transformUids";

	/** A limit on how far back to look in the datum history. */
	private static final int MAX_HISTORY_OFFSET = 1000;

	/** The maximum length of a logged message. */
	private static final int MAX_LOG_LENGTH = 600;

	/** A limit on the number of scheduled task runs. */
	private static final int MAX_TASK_RUNS = 10_000;

	private record QueuedDatum(int seq, NodeDatum datum, boolean persist, int depth,
			DatumOrigin origin) {

	}

	/** Repeated log messages of a filter. */
	private static final class LogRecord {

		private final @Nullable String filterId;
		private final @Nullable Integer datumSeq;
		private final boolean error;
		private final String message;
		private int count = 1;

		private LogRecord(@Nullable String filterId, @Nullable Integer datumSeq, boolean error,
				String message) {
			this.filterId = filterId;
			this.datumSeq = datumSeq;
			this.error = error;
			this.message = message;
		}

	}

	private record DepthLimit(@Nullable String filterId, @Nullable Integer datumSeq, String sourceId,
			String path, int count) {

	}

	private final FilterTypeRegistry registry;
	private final SimulationRequest request;
	private final SimulationOptions options;
	private final List<NodeDatum> input;
	private final SimulationContext context;
	private final FilterTracer tracer;
	private final List<SimulationMessage> messages = new ArrayList<>(8);
	private final Deque<QueuedDatum> queue = new ArrayDeque<>(16);
	private final Map<Integer, QueuedDatum> datumBySeq = new HashMap<>(16);
	private final List<DatumRecord> records = new ArrayList<>(16);
	private final Map<String, DepthLimit> depthLimits = new LinkedHashMap<>(2);
	private final Map<String, LogRecord> logs = new LinkedHashMap<>(4);

	private @Nullable QueuedDatum current;
	/** The index of the next input datum to queue. */
	private int nextInput;
	/** The filter being created or started, for its scheduled tasks. */
	private @Nullable String creating;
	/** The scheduled task running, and the datum it generated. */
	private SimTaskScheduler.@Nullable Task firing;
	private final List<QueuedDatum> taskGenerated = new ArrayList<>(2);
	private int taskRuns;
	private int nextSeq = 1;
	private int generatedCount;
	private int outputCount;
	private int discardedCount;

	/**
	 * Constructor.
	 *
	 * @param registry
	 *        the filter type registry
	 * @param expressionService
	 *        the expression service
	 * @param allowedCommands
	 *        the external commands filters may run, in addition to those the
	 *        request allows
	 * @param request
	 *        the request
	 * @throws SimulationException
	 *         if the request input datum or metadata are not valid
	 */
	SimulationRun(FilterTypeRegistry registry, ExpressionService expressionService,
			List<String> allowedCommands, SimulationRequest request) {
		super();
		this.registry = registry;
		this.request = request;
		this.options = (request.options() != null ? request.options() : SimulationOptions.DEFAULTS);
		this.input = parseInput();
		final NodeDatum first = (input.isEmpty() ? null : input.getFirst());
		final Instant firstDate = (first != null ? first.getTimestamp() : null);
		final List<String> commands = new ArrayList<>(allowedCommands);
		if ( options.allowedCommands() != null ) {
			commands.addAll(options.allowedCommands());
		}
		this.context = new SimulationContext(firstDate != null ? firstDate : defaultStartDate(),
				expressionService, this, nodeMetadata(), commands);
		registerUserMetadataServices();
		loadNodeLocation();
		loadPlaceholders();
		loadDatumMetadata();
		this.tracer = new FilterTracer(context.opModesService());
		context.setTaskOwner(() -> {
			final StepRecord step = tracer.currentStep();
			return (step != null ? step.filterId : creating);
		});
		context.localStateDao().setChangeListener(tracer::localStateChanged);
		context.instructionService().setListener(tracer::instructionIssued);
		context.metricDao().setListener(m -> tracer.metricSaved(metricInfo(m)));
		context.commandExecutor().setListener(tracer::commandRun);
	}

	/**
	 * Run the simulation.
	 *
	 * @return the result
	 * @throws SimulationException
	 *         if the request is not valid
	 */
	SimulationResult run() {
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

		final long deadline = start + TimeUnit.MILLISECONDS.toNanos(options.timeoutMsValue());
		final int maxDatum = options.maxDatumValue();
		final Instant runUntil = runUntil();
		final SimTaskScheduler scheduler = context.taskScheduler();
		while ( true ) {
			// run scheduled tasks due before the next datum, or by the end time
			final NodeDatum next = nextDatum();
			final Instant nextTime = (next != null ? timestamp(next) : runUntil);
			final SimTaskScheduler.Task task = scheduler.peek();
			if ( task != null && nextTime != null
					&& (next != null ? task.due().isBefore(nextTime) : !task.due().isAfter(nextTime)) ) {
				if ( taskRuns >= MAX_TASK_RUNS ) {
					warn(SimulationMessage.CODE_TASK_LIMIT,
							"Stopped after running %d scheduled tasks, the maximum allowed."
									.formatted(MAX_TASK_RUNS),
							null, null);
					break;
				}
				runTask(scheduler, task);
				continue;
			}
			if ( next == null ) {
				break;
			}
			if ( records.size() >= maxDatum ) {
				warn(SimulationMessage.CODE_DATUM_LIMIT,
						"Stopped after processing %d datum, the maximum allowed, so %d datum were not processed. Filters that keep generating datum can cause this."
								.formatted(maxDatum, waiting()),
						null, null);
				break;
			}
			if ( System.nanoTime() > deadline ) {
				warn(SimulationMessage.CODE_TIMEOUT,
						"Stopped after %d ms, the maximum allowed, so %d datum were not processed."
								.formatted(options.timeoutMsValue(), waiting()),
						null, null);
				break;
			}
			if ( queue.isEmpty() ) {
				enqueue(input.get(nextInput), true, 0, DatumOrigin.input(nextInput));
				nextInput++;
			}
			process(queue.removeFirst(), entry);
		}

		addPendingTaskMessages(scheduler, filters);
		shutdown(filters);
		addDepthLimitMessages();
		addLogMessages(filters);

		final SimulationState finalState = snapshot(true, null);
		final long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		return new SimulationResult(records.stream().map(DatumRecord::toTrace).toList(),
				List.copyOf(messages), finalState, new SimulationStats(records.size(), generatedCount,
						outputCount, discardedCount, durationMs));
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
			warn(SimulationMessage.CODE_FILTER_ERROR, "Error in a scheduled task of the filter %s: %s"
					.formatted(task.owner(), e.toString()), task.owner(), null);
		} finally {
			firing = null;
		}
		for ( int i = taskGenerated.size() - 1; i >= 0; i-- ) {
			queue.addFirst(taskGenerated.get(i));
		}
		taskGenerated.clear();
	}

	/** Record a message a filter logged, attributed to the filter running. */
	private void logged(boolean error, String message) {
		final StepRecord step = tracer.currentStep();
		final SimTaskScheduler.Task task = firing;
		final String filterId = (step != null ? step.filterId : task != null ? task.owner() : creating);
		final QueuedDatum datum = this.current;
		final String text = (message.length() > MAX_LOG_LENGTH
				? message.substring(0, MAX_LOG_LENGTH) + "…"
				: message);
		final String key = filterId + "\n" + text;
		final LogRecord existing = logs.get(key);
		if ( existing != null ) {
			existing.count++;
		} else {
			logs.put(key, new LogRecord(filterId, datum != null ? datum.seq() : null, error, text));
		}
	}

	private void addLogMessages(Map<String, TracingDatumFilterService> filters) {
		for ( LogRecord log : logs.values() ) {
			final TracingDatumFilterService filter = (log.filterId != null ? filters.get(log.filterId)
					: null);
			final String text = "%s logged: %s%s".formatted(filter != null ? label(filter) : "A filter",
					log.message, log.count > 1 ? " (%d times)".formatted(log.count) : "");
			messages.add(log.error
					? SimulationMessage.error(SimulationMessage.CODE_FILTER_LOG, text, log.filterId)
					: SimulationMessage.warning(SimulationMessage.CODE_FILTER_LOG, text, log.filterId,
							log.datumSeq));
		}
	}

	private void addPendingTaskMessages(SimTaskScheduler scheduler,
			Map<String, TracingDatumFilterService> filters) {
		final Map<String, Instant> next = new LinkedHashMap<>(4);
		for ( SimTaskScheduler.Task task : scheduler.pending() ) {
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
		if ( nodes.size() > options.maxDatumValue() ) {
			throw new SimulationException("Too many input datum: %d (the maximum is %d)."
					.formatted(nodes.size(), options.maxDatumValue()));
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
				warn(SimulationMessage.CODE_UNSUPPORTED_FILTER,
						"The filter type %s is not supported, so datum pass through the filter %s unchanged."
								.formatted(config.factoryUid(), label(config.id(), settings)),
						config.id(), null);
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
				tracer);
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
			warn(SimulationMessage.CODE_FILTER_ERROR,
					"Error trying to %s the filter %s: %s".formatted(action, filterId, e.toString()),
					filterId, null);
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
									.formatted(label(traced), memberId),
							config.id(), null);
					continue;
				}
				final String uid = member.getUid();
				if ( uid == null || uid.isBlank() ) {
					warn(SimulationMessage.CODE_MISSING_UID,
							"The filter %s in the chain %s has no Service Name, so the chain cannot use it."
									.formatted(memberId, label(traced)),
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
								.formatted(e.getKey(), e.getValue().size(),
										String.join(", ", e.getValue())),
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
									.formatted(label(member), String.join(" → ", cycle)),
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
			throw new SimulationException("The entry point filter %s does not exist.".formatted(id));
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
		return (uid != null && !uid.isBlank() ? uid : filterId);
	}

	private static String label(TracingDatumFilterService filter) {
		String uid = filter.getUid();
		return (uid != null && !uid.isBlank() ? uid : filter.filterId());
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
								.formatted(uid),
						null, null);
			}
			context.registerService(new SimMetadataService(uid, meta));
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
			final String label = "Datum metadata \"%s\"".formatted(sourceId);
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
		datumBySeq.put(qd.seq(), qd);
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
		records.add(record);

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
	}

	@Override
	public boolean offer(NodeDatum datum, boolean persist) {
		final QueuedDatum parent = this.current;
		final StepRecord step = tracer.currentStep();
		final int depth = (parent != null ? parent.depth() + 1 : 0);
		final String filterId = (step != null ? step.filterId : null);
		if ( depth > options.maxGenerationDepthValue() ) {
			depthLimitReached(datum, parent, filterId);
			return false;
		}
		final SimTaskScheduler.Task task = firing;
		if ( task != null && step == null ) {
			// a scheduled task: processed after the task, before later datum
			final QueuedDatum qd = new QueuedDatum(nextSeq++, datum, persist, 0,
					DatumOrigin.scheduled(task.owner()));
			datumBySeq.put(qd.seq(), qd);
			taskGenerated.add(qd);
			generatedCount++;
			return true;
		}
		final QueuedDatum qd = enqueue(datum, persist, depth, DatumOrigin.generated(
				parent != null ? parent.seq() : null, step != null ? step.id : null, filterId));
		generatedCount++;
		if ( step != null ) {
			step.generated.add(qd.seq());
		}
		return true;
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
		QueuedDatum p = parent;
		while ( p != null ) {
			path.addFirst(String.valueOf(p.datum().getSourceId()));
			Integer parentSeq = p.origin().parentSeq();
			p = (parentSeq != null ? datumBySeq.get(parentSeq) : null);
		}
		depthLimits.put(key, new DepthLimit(filterId, parent != null ? parent.seq() : null,
				String.valueOf(datum.getSourceId()), String.join(" → ", path), 1));
	}

	private void addDepthLimitMessages() {
		for ( DepthLimit limit : depthLimits.values() ) {
			warn(SimulationMessage.CODE_GENERATION_DEPTH_LIMIT,
					"Stopped a possible infinite loop: %d datum generated by the filter %s (first for the source %s) were discarded, because they exceeded the generation depth limit of %d. Generation path: %s"
							.formatted(limit.count(), limit.filterId(), limit.sourceId(),
									options.maxGenerationDepthValue(), limit.path()),
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
		return new SimulationState(modes, localState, metrics, includeCsv ? localStateCsv() : null,
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
