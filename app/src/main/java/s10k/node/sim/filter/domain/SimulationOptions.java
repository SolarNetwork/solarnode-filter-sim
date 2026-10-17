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
 * @author matt
 * @version 1.0
 */
public record SimulationOptions(@Nullable Integer maxDatum, @Nullable Integer maxGenerationDepth,
		@Nullable Long timeoutMs, @Nullable Detail detail, @Nullable Instant startDate,
		@Nullable Duration datumInterval) {

	/** The default {@code maxDatum} value. */
	public static final int DEFAULT_MAX_DATUM = 1000;

	/** The default {@code maxGenerationDepth} value. */
	public static final int DEFAULT_MAX_GENERATION_DEPTH = 10;

	/** The default {@code timeoutMs} value. */
	public static final long DEFAULT_TIMEOUT_MS = 10_000L;

	/** The default {@code datumInterval} value. */
	public static final Duration DEFAULT_DATUM_INTERVAL = Duration.ofMinutes(1);

	/** The default options. */
	public static final SimulationOptions DEFAULTS = new SimulationOptions(null, null, null, null, null,
			null);

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

}
