/* ==================================================================
 * RunService.java - 29 Oct 2026 9:15:00 pm
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
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import s10k.node.sim.filter.domain.RunInfo;
import s10k.node.sim.filter.domain.RunProgress;
import s10k.node.sim.filter.domain.RunResult;
import s10k.node.sim.filter.domain.SimulationRequest;
import s10k.node.sim.filter.domain.SimulationResult;
import s10k.node.sim.filter.sim.SimulationEngine;
import s10k.node.sim.filter.sim.SimulationException;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.util.RawValue;

/**
 * Runs simulations on their own threads, and keeps their results.
 *
 * <p>
 * A run starts at once, returning its ID, and runs on a small thread pool.
 * Browsers follow its progress with an event stream, and can cancel it. Its
 * datum traces are kept compressed as each datum is processed, with an index of
 * the datum, so a long run's result can be sent without every trace, and each
 * trace loaded when needed. The last {@link #KEEP_RUNS} runs are kept, however
 * long ago they ran, so a browser can come back for one later.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class RunService implements AutoCloseable {

	/** The number of runs kept. */
	public static final int KEEP_RUNS = 3;

	/** The most datum for which a result has every trace. */
	public static final int FULL_RESULT_DATUM = 200;

	/** The number of traces a larger result has. */
	public static final int FIRST_TRACES = 20;

	/** The most traces one request can load. */
	public static final int MAX_TRACES_PER_REQUEST = 200;

	/** The default number of runs that run at the same time. */
	public static final int DEFAULT_THREADS = 2;

	/** How often progress events are sent. */
	public static final Duration PROGRESS_INTERVAL = Duration.ofMillis(250);

	/** How long an event stream goes without an event before a comment. */
	public static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(15);

	private static final Logger log = LoggerFactory.getLogger(RunService.class);

	private final SimulationEngine engine;
	private final JsonMapper mapper;
	/** The runs, oldest first. */
	private final LinkedHashMap<String, StoredRun> runs = new LinkedHashMap<>(KEEP_RUNS + 1);
	private final ThreadPoolExecutor executor;
	private final ScheduledExecutorService scheduler;
	private final ScheduledFuture<?> tickTask;

	/**
	 * Constructor, with {@link #DEFAULT_THREADS} threads.
	 *
	 * @param engine
	 *        the simulation engine
	 * @param mapper
	 *        the JSON mapper to write traces with, the same as the web API's
	 */
	public RunService(SimulationEngine engine, JsonMapper mapper) {
		this(engine, mapper, DEFAULT_THREADS);
	}

	/**
	 * Constructor.
	 *
	 * @param engine
	 *        the simulation engine
	 * @param mapper
	 *        the JSON mapper to write traces with, the same as the web API's
	 * @param threads
	 *        the number of runs that run at the same time
	 */
	public RunService(SimulationEngine engine, JsonMapper mapper, int threads) {
		super();
		this.engine = engine;
		this.mapper = mapper;
		final AtomicInteger count = new AtomicInteger();
		this.executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
				new ArrayBlockingQueue<>(KEEP_RUNS), r -> {
					Thread t = new Thread(r, "sim-run-" + count.incrementAndGet());
					t.setDaemon(true);
					return t;
				});
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "sim-run-events");
			t.setDaemon(true);
			return t;
		});
		final long progress = PROGRESS_INTERVAL.toMillis();
		this.tickTask = scheduler.scheduleWithFixedDelay(this::tick, progress, progress,
				TimeUnit.MILLISECONDS);
	}

	/**
	 * Start a run.
	 *
	 * <p>
	 * The request is checked first, so a request that is not valid fails at
	 * once. When more than {@link #KEEP_RUNS} runs are kept, the oldest is
	 * dropped, and cancelled if it is still running.
	 * </p>
	 *
	 * @param request
	 *        the simulation request
	 * @return the run
	 * @throws SimulationException
	 *         if the request is not valid, or too many runs are waiting
	 */
	public RunInfo start(SimulationRequest request) {
		final SimulationEngine.PreparedSimulation simulation = engine.prepare(request);
		final StoredRun run = new StoredRun(UUID.randomUUID().toString(), mapper);
		final List<StoredRun> dropped = new ArrayList<>(1);
		synchronized ( runs ) {
			runs.put(run.id(), run);
			while ( runs.size() > KEEP_RUNS ) {
				final StoredRun oldest = runs.pollFirstEntry().getValue();
				dropped.add(oldest);
			}
		}
		for ( StoredRun old : dropped ) {
			if ( old.cancel() ) {
				log.info("Cancelled run {}, as newer runs replaced it", old.id());
			}
		}
		try {
			executor.execute(() -> execute(run, simulation));
		} catch ( RejectedExecutionException e ) {
			synchronized ( runs ) {
				runs.remove(run.id());
			}
			throw new SimulationException(
					"The simulator is busy with other runs. Try again when one finishes.");
		}
		return new RunInfo(run.id());
	}

	private void execute(StoredRun run, SimulationEngine.PreparedSimulation simulation) {
		try {
			final SimulationResult result = simulation.run(run);
			run.finish(result);
			log.debug("Run {} finished: {} datum, {} bytes of traces", run.id(),
					result.stats().processed(), run.storedSize());
		} catch ( SimulationException e ) {
			run.fail(e);
		} catch ( RuntimeException e ) {
			log.warn("Run {} failed: {}", run.id(), e.toString(), e);
			run.fail(new SimulationException("The simulation failed: " + e));
		}
	}

	private StoredRun run(String id) {
		final StoredRun run;
		synchronized ( runs ) {
			run = runs.get(id);
		}
		if ( run == null ) {
			throw new RunNotFoundException(id);
		}
		return run;
	}

	/**
	 * Get a run's progress.
	 *
	 * @param id
	 *        the run ID
	 * @return the progress
	 * @throws RunNotFoundException
	 *         if there is no such run
	 */
	public RunProgress progress(String id) {
		return run(id).progress();
	}

	/**
	 * Get an event stream of a run's progress: a {@code progress} event now and
	 * as it goes on, and a {@code done} event when it ends, each with a
	 * {@link RunProgress}.
	 *
	 * @param id
	 *        the run ID
	 * @return the event stream
	 * @throws RunNotFoundException
	 *         if there is no such run
	 */
	public SseEmitter events(String id) {
		return run(id).events();
	}

	/**
	 * Get a run's result.
	 *
	 * @param id
	 *        the run ID
	 * @return the result, with every trace up to {@link #FULL_RESULT_DATUM}
	 *         datum, else the first {@link #FIRST_TRACES} and an index
	 * @throws RunNotFoundException
	 *         if there is no such run
	 * @throws RunNotFinishedException
	 *         if the run is still running
	 * @throws SimulationException
	 *         if the run failed
	 */
	public RunResult result(String id) {
		return run(id).result(FULL_RESULT_DATUM, FIRST_TRACES);
	}

	/**
	 * Get the traces of some of a run's datum.
	 *
	 * @param id
	 *        the run ID
	 * @param seqs
	 *        the datum sequence numbers, up to {@link #MAX_TRACES_PER_REQUEST}
	 * @return the traces, as JSON, in the order asked, leaving out datum the
	 *         run did not process
	 * @throws RunNotFoundException
	 *         if there is no such run
	 * @throws SimulationException
	 *         if too many traces are asked for
	 */
	public List<RawValue> traces(String id, Collection<Integer> seqs) {
		if ( seqs.size() > MAX_TRACES_PER_REQUEST ) {
			throw new SimulationException("Too many datum: %d (the maximum is %d)."
					.formatted(seqs.size(), MAX_TRACES_PER_REQUEST));
		}
		return run(id).traces(seqs);
	}

	/**
	 * Get the date of a run's first datum.
	 *
	 * @param id
	 *        the run ID
	 * @return the date, or {@code null} if the run processed no datum
	 * @throws RunNotFoundException
	 *         if there is no such run
	 */
	public @Nullable Instant startDate(String id) {
		return run(id).startDate();
	}

	/**
	 * Write a run's datum as a JSON array, in the format of the Datum input.
	 *
	 * @param id
	 *        the run ID
	 * @param output
	 *        {@code true} for the datum the filters output, to be persisted;
	 *        {@code false} for each datum as it entered the Datum Queue
	 * @param out
	 *        the stream to write to
	 * @throws IOException
	 *         if writing fails
	 * @throws RunNotFoundException
	 *         if there is no such run
	 * @throws RunNotFinishedException
	 *         if the run is still running
	 */
	public void writeDatum(String id, boolean output, OutputStream out) throws IOException {
		run(id).writeDatum(output ? StoredRun.DatumKind.Output : StoredRun.DatumKind.Input, out);
	}

	/**
	 * Check that a run's datum can be written.
	 *
	 * @param id
	 *        the run ID
	 * @throws RunNotFoundException
	 *         if there is no such run
	 * @throws RunNotFinishedException
	 *         if the run is still running
	 */
	public void checkFinished(String id) {
		final StoredRun run = run(id);
		if ( run.state() == RunProgress.State.Running ) {
			throw new RunNotFinishedException(id);
		}
	}

	/**
	 * Cancel a run: it stops, and keeps what it has.
	 *
	 * @param id
	 *        the run ID
	 * @return {@code true} if the run was running
	 * @throws RunNotFoundException
	 *         if there is no such run
	 */
	public boolean cancel(String id) {
		return run(id).cancel();
	}

	private List<StoredRun> runs() {
		synchronized ( runs ) {
			return List.copyOf(runs.values());
		}
	}

	private void tick() {
		try {
			for ( StoredRun run : runs() ) {
				run.tick(KEEP_ALIVE_INTERVAL);
			}
		} catch ( RuntimeException e ) {
			log.warn("Run progress events failed: {}", e.toString(), e);
		}
	}

	/**
	 * Cancel the runs, and stop the threads.
	 */
	@Override
	public void close() {
		for ( StoredRun run : runs() ) {
			run.cancel();
		}
		tickTask.cancel(false);
		scheduler.shutdownNow();
		executor.shutdown();
	}

}
