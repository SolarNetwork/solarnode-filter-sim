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
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.node.domain.LocalState;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.runtime.DatumHistory;
import net.solarnetwork.node.runtime.DefaultLocalStateService;
import net.solarnetwork.node.runtime.DefaultOperationalModesService;
import net.solarnetwork.node.service.DatumHistorian;
import net.solarnetwork.node.service.DatumQueueProcessObserver.Stage;
import net.solarnetwork.node.service.support.DatumFilterChainService;
import net.solarnetwork.service.DatumFilterService;
import net.solarnetwork.service.ExpressionService;
import net.solarnetwork.service.StaticOptionalService;
import s10k.node.sim.filter.domain.DatumInfo;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.FilterConfig;
import s10k.node.sim.filter.domain.LocalStateInfo;
import s10k.node.sim.filter.domain.SimConfig;
import s10k.node.sim.filter.domain.SimulationEnvironment;
import s10k.node.sim.filter.domain.SimulationHistory;
import s10k.node.sim.filter.domain.SimulationMessage;
import s10k.node.sim.filter.domain.SimulationOptions;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.domain.SimulationState;
import s10k.node.sim.filter.domain.SimulationStats;
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

	private record QueuedDatum(int seq, NodeDatum datum, boolean persist, int depth,
			DatumOrigin origin) {

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

	private @Nullable QueuedDatum current;
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
	 * @param request
	 *        the request
	 * @throws SimulationException
	 *         if the request input datum are not valid
	 */
	SimulationRun(FilterTypeRegistry registry, ExpressionService expressionService,
			SimulationRequest request) {
		super();
		this.registry = registry;
		this.request = request;
		this.options = (request.options() != null ? request.options() : SimulationOptions.DEFAULTS);
		this.input = parseInput();
		final NodeDatum first = (input.isEmpty() ? null : input.getFirst());
		final Instant firstDate = (first != null ? first.getTimestamp() : null);
		this.context = new SimulationContext(firstDate != null ? firstDate : defaultStartDate(),
				expressionService, this);
		this.tracer = new FilterTracer(context.opModesService());
		context.localStateDao().setChangeListener(tracer::localStateChanged);
		context.instructionService().setListener(tracer::instructionIssued);
	}

	/**
	 * Run the simulation.
	 *
	 * @return the result
	 * @throws SimulationException
	 *         if the request is not valid
	 */
	SimulationResult run() {
		final long start = System.nanoTime();
		final Map<String, TracingDatumFilterService> filters = createFilters();
		final TracingDatumFilterService entry = entryFilter(filters);
		final Instant firstDate = context.simulatedClock().instant();
		loadEnvironment(firstDate);
		loadHistory(firstDate);

		for ( int i = 0; i < input.size(); i++ ) {
			enqueue(input.get(i), true, 0, DatumOrigin.input(i));
		}

		final long deadline = start + TimeUnit.MILLISECONDS.toNanos(options.timeoutMsValue());
		final int maxDatum = options.maxDatumValue();
		while ( !queue.isEmpty() ) {
			if ( records.size() >= maxDatum ) {
				warn(SimulationMessage.CODE_DATUM_LIMIT,
						"Stopped after processing %d datum, the maximum allowed, so %d datum were not processed. Filters that keep generating datum can cause this."
								.formatted(maxDatum, queue.size()),
						null, null);
				break;
			}
			if ( System.nanoTime() > deadline ) {
				warn(SimulationMessage.CODE_TIMEOUT,
						"Stopped after %d ms, the maximum allowed, so %d datum were not processed."
								.formatted(options.timeoutMsValue(), queue.size()),
						null, null);
				break;
			}
			process(queue.removeFirst(), entry);
		}

		shutdown(filters);
		addDepthLimitMessages();

		final SimulationState finalState = snapshot(true, null);
		final long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
		return new SimulationResult(records.stream().map(DatumRecord::toTrace).toList(),
				List.copyOf(messages), finalState, new SimulationStats(records.size(), generatedCount,
						outputCount, discardedCount, durationMs));
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
			warn(SimulationMessage.CODE_UNSUPPORTED_FILTER,
					"The filter type %s is not supported, so datum pass through the filter %s unchanged."
							.formatted(config.factoryUid(), label(config.id(), settings)),
					config.id(), null);
			return new TracingDatumFilterService(config.id(), config.factoryUid(),
					new UnsupportedDatumFilterService(config.factoryUid(), settings.get(UID_SETTING)),
					false, tracer);
		}
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
		final String limitation = def.limitations(settings);
		if ( limitation != null ) {
			warn(SimulationMessage.CODE_FILTER_LIMITATION, limitation, config.id(), null);
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
			final List<String> uids = new ArrayList<>(config.filterIdsOrEmpty().size());
			for ( String memberId : config.filterIdsOrEmpty() ) {
				final TracingDatumFilterService member = (memberId != null ? filters.get(memberId)
						: null);
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
		return new SimulationState(modes, localState, includeCsv ? localStateCsv() : null,
				history(context.datumService()), history(context.datumService().unfiltered()), queued);
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
