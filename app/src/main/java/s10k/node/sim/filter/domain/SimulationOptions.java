/* ==================================================================
 * SimulationOptions.java - 18 Oct 2026 8:56:20 am
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

package s10k.node.sim.filter.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Options for running a simulation.
 *
 * @param maxDatum
 *        the maximum number of datum to process, including generated datum, or
 *        {@code null} for {@link #DEFAULT_MAX_DATUM}
 * @param maxGenerationDepth
 *        the maximum number of generations of generated datum (a datum
 *        generated while processing a generated datum is one generation
 *        deeper), or {@code null} for {@link #DEFAULT_MAX_GENERATION_DEPTH}
 * @param timeoutMs
 *        the maximum time to spend processing datum, in milliseconds, or
 *        {@code null} for {@link #DEFAULT_TIMEOUT_MS}
 * @param detail
 *        the level of detail to include in the result, or {@code null} for
 *        {@link Detail#Summary}
 * @param startDate
 *        the timestamp to give the first input datum when it has none, or
 *        {@code null} for the current time
 * @param datumInterval
 *        the time between input datum that have no timestamp, or {@code null}
 *        for {@link #DEFAULT_DATUM_INTERVAL}
 * @param runAfter
 *        how long the simulated clock keeps running after the last input datum,
 *        so that scheduled tasks due by then run, or {@code null} to stop after
 *        the last datum
 * @param allowedCommands
 *        the external commands filters may run, as the exact command paths, in
 *        addition to those the simulator is configured to allow, or
 *        {@code null} for none
 * @param instructionJobSeconds
 *        the schedule of the simulated Instruction Execution Job, which runs
 *        stored instructions once they are due: every second of the minute that
 *        is a multiple of this number, like the node's {@code 0/10 * * * * ?}
 *        cron schedule for {@code 10}, or {@code null} for
 *        {@link #DEFAULT_INSTRUCTION_JOB_SECONDS}
 * @author matt
 * @version 1.1
 */
public record SimulationOptions(@Nullable Integer maxDatum, @Nullable Integer maxGenerationDepth,
		@Nullable Long timeoutMs, @Nullable Detail detail, @Nullable Instant startDate,
		@Nullable Duration datumInterval, @Nullable Duration runAfter,
		@Nullable List<String> allowedCommands, @Nullable Integer instructionJobSeconds) {

	/** The default {@code maxDatum} value. */
	public static final int DEFAULT_MAX_DATUM = 1000;

	/** The default {@code maxGenerationDepth} value. */
	public static final int DEFAULT_MAX_GENERATION_DEPTH = 10;

	/** The default {@code timeoutMs} value. */
	public static final long DEFAULT_TIMEOUT_MS = 10_000L;

	/**
	 * The default {@code datumInterval} value: short, so a run of a few input
	 * datum does not leave time for scheduled tasks to run many times.
	 */
	public static final Duration DEFAULT_DATUM_INTERVAL = Duration.ofSeconds(1);

	/**
	 * The default {@code instructionJobSeconds} value, like the node's
	 * {@code job.instructionExecution.cron} default of {@code 0/10 * * * * ?}.
	 */
	public static final int DEFAULT_INSTRUCTION_JOB_SECONDS = 10;

	/** The default options. */
	public static final SimulationOptions DEFAULTS = new SimulationOptions(null, null, null, null, null,
			null, null, null, null);

	/**
	 * Constructor, with the default Instruction Execution Job schedule.
	 *
	 * @param maxDatum
	 *        the maximum number of datum to process
	 * @param maxGenerationDepth
	 *        the maximum generation depth
	 * @param timeoutMs
	 *        the processing timeout, in milliseconds
	 * @param detail
	 *        the level of detail
	 * @param startDate
	 *        the timestamp to give the first input datum when it has none
	 * @param datumInterval
	 *        the time between input datum that have no timestamp
	 * @param runAfter
	 *        how long the simulated clock keeps running after the last input
	 *        datum
	 * @param allowedCommands
	 *        the external commands filters may run
	 */
	public SimulationOptions(@Nullable Integer maxDatum, @Nullable Integer maxGenerationDepth,
			@Nullable Long timeoutMs, @Nullable Detail detail, @Nullable Instant startDate,
			@Nullable Duration datumInterval, @Nullable Duration runAfter,
			@Nullable List<String> allowedCommands) {
		this(maxDatum, maxGenerationDepth, timeoutMs, detail, startDate, datumInterval, runAfter,
				allowedCommands, null);
	}

	/**
	 * The level of detail to include in a simulation result.
	 */
	public enum Detail {

		/** The datum and filter steps, with the changes each step made. */
		Summary,

		/**
		 * Everything in {@link #Summary}, plus a snapshot of the simulated node
		 * state when each datum starts processing, for debug stepping.
		 */
		Debug,

	}

	/**
	 * Get the maximum number of datum to process.
	 *
	 * @return the maximum datum
	 */
	public int maxDatumValue() {
		return (maxDatum != null && maxDatum > 0 ? maxDatum : DEFAULT_MAX_DATUM);
	}

	/**
	 * Get the maximum generation depth.
	 *
	 * @return the maximum generation depth
	 */
	public int maxGenerationDepthValue() {
		return (maxGenerationDepth != null && maxGenerationDepth >= 0 ? maxGenerationDepth
				: DEFAULT_MAX_GENERATION_DEPTH);
	}

	/**
	 * Get the processing timeout.
	 *
	 * @return the timeout, in milliseconds
	 */
	public long timeoutMsValue() {
		return (timeoutMs != null && timeoutMs > 0 ? timeoutMs : DEFAULT_TIMEOUT_MS);
	}

	/**
	 * Get the detail level.
	 *
	 * @return the detail level
	 */
	public Detail detailValue() {
		return (detail != null ? detail : Detail.Summary);
	}

	/**
	 * Get the interval between input datum without timestamps.
	 *
	 * @return the interval
	 */
	public Duration datumIntervalValue() {
		return (datumInterval != null && !datumInterval.isNegative() ? datumInterval
				: DEFAULT_DATUM_INTERVAL);
	}

	/**
	 * Get how long the simulated clock keeps running after the last input
	 * datum.
	 *
	 * @return the duration, never negative
	 */
	public Duration runAfterValue() {
		return (runAfter != null && !runAfter.isNegative() ? runAfter : Duration.ZERO);
	}

	/**
	 * Get the Instruction Execution Job schedule.
	 *
	 * @return the number of seconds, at least {@code 1}
	 */
	public int instructionJobSecondsValue() {
		return (instructionJobSeconds != null && instructionJobSeconds > 0 ? instructionJobSeconds
				: DEFAULT_INSTRUCTION_JOB_SECONDS);
	}

}
