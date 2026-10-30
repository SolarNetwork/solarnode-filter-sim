/* ==================================================================
 * StoredRun.java - 29 Oct 2026 9:00:00 pm
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

package s10k.node.sim.filter.run;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import s10k.node.sim.filter.domain.DatumIndexEntry;
import s10k.node.sim.filter.domain.DatumInfo;
import s10k.node.sim.filter.domain.DatumTrace;
import s10k.node.sim.filter.domain.FilterRunStats;
import s10k.node.sim.filter.domain.FilterStep;
import s10k.node.sim.filter.domain.RunProgress;
import s10k.node.sim.filter.domain.RunResult;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.sim.SimulationException;
import s10k.node.sim.filter.sim.SimulationObserver;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MappingIterator;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.RawValue;

/**
 * A simulation run kept on the server: it observes the simulation as it runs,
 * storing each datum's trace and the datum to download, and sends its progress
 * to the browsers watching it.
 *
 * @author matt
 * @version 1.1
 */
final class StoredRun implements SimulationObserver {

	/** How long an event stream stays open. */
	static final Duration EVENTS_TIMEOUT = Duration.ofMinutes(30);

	/** The kinds of datum to download. */
	enum DatumKind {

		/** Each datum as it entered the Datum Queue, before the filters. */
		Input,

		/** Each datum the filters output, to be persisted. */
		Output,

	}

	private final String id;
	private final JsonMapper mapper;
	private final TraceStore traces = new TraceStore();
	private final JsonArrayStore inputs = new JsonArrayStore();
	private final JsonArrayStore outputs = new JsonArrayStore();
	private final DatumCsv.Columns inputColumns = new DatumCsv.Columns();
	private final DatumCsv.Columns outputColumns = new DatumCsv.Columns();
	private final List<DatumIndexEntry> index = new ArrayList<>(64);
	private final Map<Integer, Integer> positions = new HashMap<>(64);
	private final Map<String, int[]> filterStats = new LinkedHashMap<>(8);
	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

	private volatile RunProgress.State state = RunProgress.State.Running;
	private volatile boolean cancelled;
	private volatile int expected;
	private volatile int processed;
	private volatile @Nullable Instant date;

	private @Nullable SimulationResult result;
	private @Nullable SimulationException failure;

	/** The processed count last sent, and when an event was last sent. */
	private int sentProcessed = -1;
	private long sentAt;

	/**
	 * Constructor.
	 *
	 * @param id
	 *        the run ID
	 * @param mapper
	 *        the JSON mapper to write traces and datum with, the same as the
	 *        web API's
	 */
	StoredRun(String id, JsonMapper mapper) {
		super();
		this.id = id;
		this.mapper = mapper;
	}

	/**
	 * Get the run ID.
	 *
	 * @return the ID
	 */
	String id() {
		return id;
	}

	/**
	 * Get the run's state.
	 *
	 * @return the state
	 */
	RunProgress.State state() {
		return state;
	}

	/* === SimulationObserver === */

	@Override
	public void started(int expected) {
		this.expected = expected;
	}

	@Override
	public void processed(DatumTrace trace) {
		traces.add(mapper.writeValueAsBytes(trace));
		synchronized ( this ) {
			positions.put(trace.seq(), index.size());
			index.add(DatumIndexEntry.of(trace));
			for ( FilterStep step : trace.steps() ) {
				count(filterStats.computeIfAbsent(step.filterId(), _ -> new int[6]), step.status());
			}
		}
		final DownloadDatum input = DownloadDatum.of(trace.input());
		inputs.add(mapper.writeValueAsBytes(input));
		inputColumns.add(input);
		final DatumInfo output = trace.output();
		if ( output != null && trace.persist() ) {
			final DownloadDatum datum = DownloadDatum.of(output);
			outputs.add(mapper.writeValueAsBytes(datum));
			outputColumns.add(datum);
		}
		final int n = processed + 1;
		processed = n;
		date = trace.input().timestamp();
		if ( n > expected ) {
			expected = n;
		}
	}

	/** Count a step like the UI's {@code filterRunStats()}. */
	private static void count(int[] s, FilterStep.Status status) {
		s[0]++;
		if ( status == FilterStep.Status.Dropped ) {
			s[2]++;
		} else if ( status == FilterStep.Status.Error ) {
			s[5]++;
		} else {
			s[1]++;
		}
		if ( status == FilterStep.Status.Changed ) {
			s[3]++;
		} else if ( status == FilterStep.Status.Skipped ) {
			s[4]++;
		}
	}

	@Override
	public boolean cancelled() {
		return cancelled;
	}

	/**
	 * Cancel the run, which then stops and keeps what it has.
	 *
	 * @return {@code true} if the run was running
	 */
	boolean cancel() {
		if ( state != RunProgress.State.Running ) {
			return false;
		}
		cancelled = true;
		return true;
	}

	/* === Finishing === */

	/**
	 * Finish the run with its result.
	 *
	 * @param result
	 *        the result, without the datum traces
	 */
	void finish(SimulationResult result) {
		inputs.finish();
		outputs.finish();
		synchronized ( this ) {
			this.result = result;
			end(RunProgress.State.Done);
		}
	}

	/**
	 * Finish the run that failed.
	 *
	 * @param failure
	 *        why it failed
	 */
	void fail(SimulationException failure) {
		inputs.finish();
		outputs.finish();
		synchronized ( this ) {
			this.failure = failure;
			end(RunProgress.State.Failed);
		}
	}

	private void end(RunProgress.State state) {
		this.state = state;
		broadcast(() -> event("done"));
		for ( SseEmitter emitter : emitters ) {
			emitter.complete();
		}
		emitters.clear();
	}

	/* === Events === */

	/**
	 * Get the run's progress.
	 *
	 * @return the progress
	 */
	synchronized RunProgress progress() {
		final SimulationException f = failure;
		return new RunProgress(state, processed, expected, date, f != null ? f.getMessage() : null,
				f != null ? f.problems() : null);
	}

	/**
	 * Get an event stream of the run's progress: a {@code progress} event now
	 * and as the run goes on, then a {@code done} event when it ends.
	 *
	 * @return the event stream
	 */
	SseEmitter events() {
		final SseEmitter emitter = new SseEmitter(EVENTS_TIMEOUT.toMillis());
		synchronized ( this ) {
			try {
				if ( state == RunProgress.State.Running ) {
					emitter.send(event("progress"));
				} else {
					emitter.send(event("done"));
					emitter.complete();
					return emitter;
				}
			} catch ( IOException e ) {
				emitter.completeWithError(e);
				return emitter;
			}
			emitters.add(emitter);
		}
		final Runnable remove = () -> remove(emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(_ -> remove(emitter));
		return emitter;
	}

	private void remove(SseEmitter emitter) {
		emitters.remove(emitter);
	}

	/**
	 * Send the progress to the browsers watching, if it changed, or a comment
	 * to keep the streams open.
	 *
	 * @param keepAlive
	 *        how long without an event before a comment is sent
	 */
	synchronized void tick(Duration keepAlive) {
		if ( state != RunProgress.State.Running || emitters.isEmpty() ) {
			return;
		}
		final long now = System.currentTimeMillis();
		final int n = processed;
		if ( n != sentProcessed ) {
			sentProcessed = n;
			sentAt = now;
			broadcast(() -> event("progress"));
		} else if ( now - sentAt > keepAlive.toMillis() ) {
			sentAt = now;
			broadcast(() -> SseEmitter.event().comment("keep-alive"));
		}
	}

	// each send needs its own event builder
	private void broadcast(Supplier<SseEmitter.SseEventBuilder> event) {
		for ( SseEmitter emitter : emitters ) {
			try {
				emitter.send(event.get());
			} catch ( IOException | IllegalStateException e ) {
				remove(emitter);
			}
		}
	}

	private SseEmitter.SseEventBuilder event(String name) {
		return SseEmitter.event().name(name).data(mapper.writeValueAsString(progress()),
				MediaType.APPLICATION_JSON);
	}

	/* === Results === */

	/**
	 * Get the run's result.
	 *
	 * @param fullResultDatum
	 *        the most datum for which the result has every trace
	 * @param firstTraces
	 *        the number of traces a larger result has
	 * @return the result
	 * @throws RunNotFinishedException
	 *         if the run is still running
	 * @throws SimulationException
	 *         if the run failed
	 */
	RunResult result(int fullResultDatum, int firstTraces) {
		final SimulationResult r;
		final List<DatumIndexEntry> entries;
		final Map<String, FilterRunStats> stats;
		synchronized ( this ) {
			if ( failure != null ) {
				throw failure;
			}
			if ( result == null ) {
				throw new RunNotFinishedException(id);
			}
			r = result;
			entries = List.copyOf(index);
			stats = new LinkedHashMap<>(filterStats.size());
			for ( Map.Entry<String, int[]> e : filterStats.entrySet() ) {
				final int[] s = e.getValue();
				stats.put(e.getKey(), new FilterRunStats(s[0], s[1], s[2], s[3], s[4], s[5]));
			}
		}
		final boolean full = entries.size() <= fullResultDatum;
		final int n = (full ? entries.size() : Math.min(firstTraces, entries.size()));
		final List<RawValue> datum = new ArrayList<>(n);
		for ( int i = 0; i < n; i++ ) {
			datum.add(new RawValue(traces.get(i)));
		}
		return new RunResult(id, datum, r.instructionRuns(), r.messages(), r.finalState(), r.stats(),
				full ? null : entries, full ? null : stats);
	}

	/**
	 * Get the traces of some datum.
	 *
	 * @param seqs
	 *        the datum sequence numbers
	 * @return the traces of the datum the run processed, in the order asked, as
	 *         JSON
	 */
	List<RawValue> traces(Collection<Integer> seqs) {
		final List<RawValue> result = new ArrayList<>(seqs.size());
		for ( Integer seq : seqs ) {
			final Integer pos;
			synchronized ( this ) {
				pos = positions.get(seq);
			}
			if ( pos != null ) {
				result.add(new RawValue(traces.get(pos)));
			}
		}
		return result;
	}

	/**
	 * Get the date of the first datum processed.
	 *
	 * @return the date, or {@code null} if there is none
	 */
	synchronized @Nullable Instant startDate() {
		return (index.isEmpty() ? null : index.getFirst().created());
	}

	/**
	 * Write the datum as a JSON array.
	 *
	 * @param kind
	 *        the datum to write
	 * @param out
	 *        the stream to write to
	 * @throws IOException
	 *         if writing fails
	 * @throws RunNotFinishedException
	 *         if the run is still running
	 */
	void writeDatum(DatumKind kind, OutputStream out) throws IOException {
		if ( state == RunProgress.State.Running ) {
			throw new RunNotFinishedException(id);
		}
		(kind == DatumKind.Input ? inputs : outputs).writeTo(out);
	}

	/**
	 * Write the datum as CSV.
	 *
	 * @param kind
	 *        the datum to write
	 * @param nodeId
	 *        the node ID to write
	 * @param bySource
	 *        {@code true} for a zip archive of one CSV per source ID,
	 *        {@code false} for one CSV
	 * @param out
	 *        the stream to write to
	 * @throws IOException
	 *         if writing fails
	 * @throws RunNotFinishedException
	 *         if the run is still running
	 * @see DatumCsv
	 */
	void writeCsv(DatumKind kind, long nodeId, boolean bySource, OutputStream out) throws IOException {
		if ( state == RunProgress.State.Running ) {
			throw new RunNotFinishedException(id);
		}
		final DatumCsv.Columns columns = (kind == DatumKind.Input ? inputColumns : outputColumns);
		// decimals stay exact
		try (InputStream in = (kind == DatumKind.Input ? inputs : outputs).read();
				MappingIterator<DownloadDatum> datum = mapper.readerFor(DownloadDatum.class)
						.with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValues(in)) {
			if ( bySource ) {
				DatumCsv.writeZip(datum, columns, nodeId, out);
			} else {
				DatumCsv.write(datum, columns, nodeId, out);
			}
		}
	}

	/**
	 * Get the size of the stored traces.
	 *
	 * @return the compressed size, in bytes
	 */
	long storedSize() {
		return traces.compressedSize();
	}

}
