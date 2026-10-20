/* ==================================================================
 * FilterTracer.java - 18 Oct 2026 8:56:20 am
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
import java.time.temporal.TemporalAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.CommandInfo;
import s10k.node.sim.filter.domain.DatumInfo;
import s10k.node.sim.filter.domain.DatumOrigin;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.InstructionInfo;
import s10k.node.sim.filter.domain.LocalStateChange;
import s10k.node.sim.filter.domain.MetricInfo;
import s10k.node.sim.filter.domain.OpModeChange;
import s10k.node.sim.filter.domain.SamplesInfo;
import s10k.node.sim.filter.domain.SimulationState;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.node.domain.LocalState;
import net.solarnetwork.node.service.OperationalModesService;
import net.solarnetwork.node.service.support.BaseDatumFilterSupport;
import net.solarnetwork.service.DatumFilterStats;

/**
 * Records filter invocations while the simulation processes a datum.
 *
 * <p>
 * Changes the simulated services observe, like Local State changes, issued
 * instructions, saved metrics, and external commands, are attributed to the
 * innermost filter step running at the time.
 * </p>
 *
 * <p>
 * A filter is "skipped" when its own {@link DatumFilterStats#Ignored} statistic
 * increases. SolarNode filters count a datum as ignored when they do not
 * process it, such as when their source ID, required mode, or required tag
 * conditions do not match, or when they are missing configuration they need.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class FilterTracer {

	/** The prefix of filter parameter names used internally by SolarNode. */
	public static final String INTERNAL_PARAMETER_PREFIX = "__";

	private final OperationalModesService opModesService;
	private final Deque<StepRecord> active = new ArrayDeque<>(4);
	private @Nullable DatumRecord datum;
	private int nextStepId = 1;

	/**
	 * Constructor.
	 *
	 * @param opModesService
	 *        the operational modes service, to observe mode changes
	 */
	public FilterTracer(OperationalModesService opModesService) {
		super();
		this.opModesService = opModesService;
	}

	/**
	 * Start recording the processing of a datum.
	 *
	 * @param record
	 *        the datum record
	 */
	void beginDatum(DatumRecord record) {
		this.datum = record;
		active.clear();
	}

	/**
	 * Stop recording the processing of a datum.
	 */
	void endDatum() {
		this.datum = null;
		active.clear();
	}

	/**
	 * Get the innermost filter step running now.
	 *
	 * @return the step, or {@code null} if no filter is running
	 */
	@Nullable
	StepRecord currentStep() {
		return active.peek();
	}

	/**
	 * Invoke a filter, recording the invocation as a step of the current datum.
	 *
	 * @param filter
	 *        the filter
	 * @param datum
	 *        the datum
	 * @param samples
	 *        the samples
	 * @param parameters
	 *        the parameters
	 * @return the filter result
	 */
	@Nullable
	DatumSamplesOperations invoke(TracingDatumFilterService filter, Datum datum,
			DatumSamplesOperations samples, @Nullable Map<String, Object> parameters) {
		final DatumRecord record = this.datum;
		if ( record == null ) {
			return filter.delegate().filter(datum, samples, parameters);
		}
		final StepRecord step = new StepRecord(nextStepId++, filter, active.size(),
				SamplesInfo.from(samples));
		record.steps.add(step);
		final boolean leaf = !filter.isChain();
		final Map<String, Object> paramsBefore = (leaf ? sanitize(parameters) : Map.of());
		final Set<String> modesBefore = (leaf ? activeModes() : Set.of());
		final long ignoredBefore = ignoredCount(filter.delegate());
		active.push(step);
		final long start = System.nanoTime();
		DatumSamplesOperations out = null;
		RuntimeException error = null;
		try {
			out = filter.delegate().filter(datum, samples, parameters);
			return out;
		} catch ( RuntimeException e ) {
			error = e;
			throw e;
		} finally {
			step.durationMicros = (System.nanoTime() - start) / 1000L;
			active.pop();
			if ( error != null ) {
				step.status = FilterStep.Status.Error;
				step.error = (error.getMessage() != null ? error.getMessage() : error.toString());
			} else if ( filter.delegate() instanceof UnsupportedDatumFilterService ) {
				step.status = FilterStep.Status.Unsupported;
			} else if ( out == null ) {
				step.status = FilterStep.Status.Dropped;
			} else {
				finishOutput(step, filter, datum, out, ignoredBefore);
			}
			if ( leaf ) {
				Map<String, Object> paramsAfter = sanitize(parameters);
				if ( !paramsAfter.equals(paramsBefore) ) {
					step.parameters = paramsAfter;
				}
				Set<String> modesAfter = activeModes();
				if ( !modesAfter.equals(modesBefore) ) {
					Set<String> enabled = new TreeSet<>(modesAfter);
					enabled.removeAll(modesBefore);
					Set<String> disabled = new TreeSet<>(modesBefore);
					disabled.removeAll(modesAfter);
					step.opModes = new OpModeChange(enabled, disabled);
				}
			}
		}
	}

	private void finishOutput(StepRecord step, TracingDatumFilterService filter, Datum datum,
			DatumSamplesOperations out, long ignoredBefore) {
		final SamplesInfo output = SamplesInfo.from(out);
		if ( out instanceof Datum d && d.getTimestamp() != null
				&& !d.getTimestamp().equals(datum.getTimestamp()) ) {
			step.outputTimestamp = d.getTimestamp();
		}
		final long ignoredAfter = ignoredCount(filter.delegate());
		if ( ignoredBefore >= 0 && ignoredAfter > ignoredBefore ) {
			step.status = FilterStep.Status.Skipped;
		} else if ( output.equals(step.input) && step.outputTimestamp == null ) {
			step.status = FilterStep.Status.Unchanged;
		} else {
			step.status = FilterStep.Status.Changed;
			step.output = output;
		}
	}

	/**
	 * Handle a Local State change.
	 *
	 * @param key
	 *        the key
	 * @param previous
	 *        the previous entity, or {@code null}
	 * @param current
	 *        the current entity, or {@code null}
	 */
	void localStateChanged(String key, @Nullable LocalState previous, @Nullable LocalState current) {
		final StepRecord step = currentStep();
		if ( step != null ) {
			step.localState
					.add(new LocalStateChange(key, current != null ? current.getType().name() : null,
							current != null ? current.getValue() : null,
							previous != null ? previous.getValue() : null));
		}
	}

	/**
	 * Handle an issued instruction.
	 *
	 * @param instruction
	 *        the instruction
	 */
	void instructionIssued(InstructionInfo instruction) {
		final StepRecord step = currentStep();
		if ( step != null ) {
			step.instructions.add(instruction);
		}
	}

	/**
	 * Handle a saved metric.
	 *
	 * @param metric
	 *        the metric
	 */
	void metricSaved(MetricInfo metric) {
		final StepRecord step = currentStep();
		if ( step != null ) {
			step.metrics.add(metric);
		}
	}

	/**
	 * Handle an external command a filter asked to run.
	 *
	 * @param command
	 *        the command
	 */
	void commandRun(CommandInfo command) {
		final StepRecord step = currentStep();
		if ( step != null ) {
			step.commands.add(command);
		}
	}

	private Set<String> activeModes() {
		Set<String> modes = opModesService.activeOperationalModes();
		return (modes != null ? new TreeSet<>(modes) : new TreeSet<>());
	}

	/**
	 * Get the number of datum a filter has ignored.
	 *
	 * @param filter
	 *        the filter
	 * @return the ignored count, or {@code -1} if the filter does not provide
	 *         statistics
	 */
	private static long ignoredCount(Object filter) {
		return (filter instanceof BaseDatumFilterSupport f
				? f.getStatisticCount(DatumFilterStats.Ignored)
				: -1);
	}

	/**
	 * Copy filter parameters into a form suitable for JSON, without the
	 * parameters SolarNode uses internally.
	 *
	 * @param parameters
	 *        the parameters
	 * @return the copy, never {@code null}
	 */
	static Map<String, Object> sanitize(@Nullable Map<String, ?> parameters) {
		if ( parameters == null || parameters.isEmpty() ) {
			return Map.of();
		}
		Map<String, Object> result = new LinkedHashMap<>(parameters.size());
		for ( Map.Entry<String, ?> e : parameters.entrySet() ) {
			if ( e.getKey() == null || e.getKey().startsWith(INTERNAL_PARAMETER_PREFIX) ) {
				continue;
			}
			result.put(e.getKey(), sanitizeValue(e.getValue()));
		}
		return result;
	}

	private static @Nullable Object sanitizeValue(@Nullable Object v) {
		if ( v == null || v instanceof Number || v instanceof String || v instanceof Boolean ) {
			return v;
		}
		if ( v instanceof Map<?, ?> m ) {
			Map<String, Object> result = new LinkedHashMap<>(m.size());
			for ( Map.Entry<?, ?> e : m.entrySet() ) {
				result.put(String.valueOf(e.getKey()), sanitizeValue(e.getValue()));
			}
			return result;
		}
		if ( v instanceof Collection<?> c ) {
			List<@Nullable Object> result = new ArrayList<>(c.size());
			for ( Object o : c ) {
				result.add(sanitizeValue(o));
			}
			return result;
		}
		if ( v instanceof TemporalAccessor ) {
			return v.toString();
		}
		return String.valueOf(v);
	}

	/**
	 * A datum being processed.
	 */
	static final class DatumRecord {

		final int seq;
		final DatumOrigin origin;
		final DatumInfo input;
		final boolean persist;
		final List<StepRecord> steps = new ArrayList<>(8);
		@Nullable
		DatumInfo output;
		@Nullable
		String error;
		@Nullable
		SimulationState startState;

		DatumRecord(int seq, DatumOrigin origin, DatumInfo input, boolean persist) {
			super();
			this.seq = seq;
			this.origin = origin;
			this.input = input;
			this.persist = persist;
		}

		DatumTrace toTrace() {
			return new DatumTrace(seq, origin, input, persist,
					steps.stream().map(StepRecord::toStep).toList(), output, error, startState);
		}

	}

	/**
	 * A filter step being recorded.
	 */
	static final class StepRecord {

		final int id;
		final String filterId;
		final @Nullable String uid;
		final String factoryUid;
		final int depth;
		final SamplesInfo input;
		final List<LocalStateChange> localState = new ArrayList<>(2);
		final List<Integer> generated = new ArrayList<>(2);
		final List<InstructionInfo> instructions = new ArrayList<>(2);
		final List<MetricInfo> metrics = new ArrayList<>(2);
		final List<CommandInfo> commands = new ArrayList<>(1);
		FilterStep.Status status = FilterStep.Status.Unchanged;
		@Nullable
		SamplesInfo output;
		@Nullable
		Instant outputTimestamp;
		@Nullable
		Map<String, Object> parameters;
		@Nullable
		OpModeChange opModes;
		@Nullable
		String error;
		long durationMicros;

		StepRecord(int id, TracingDatumFilterService filter, int depth, SamplesInfo input) {
			super();
			this.id = id;
			this.filterId = filter.filterId();
			this.uid = filter.getUid();
			this.factoryUid = filter.factoryUid();
			this.depth = depth;
			this.input = input;
		}

		FilterStep toStep() {
			return new FilterStep(id, filterId, uid, factoryUid, depth, status, input, output,
					outputTimestamp, parameters, opModes, localState, generated, instructions, metrics,
					commands, error, durationMicros);
		}

	}

}
