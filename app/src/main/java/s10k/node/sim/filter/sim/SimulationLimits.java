/* ==================================================================
 * SimulationLimits.java - 29 Oct 2026 8:10:00 pm
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
import s10k.node.sim.filter.domain.SimulationOptions;

/**
 * The server's limits on simulations, which the limits of each run scale
 * within.
 *
 * <p>
 * A run's Maximum Datum is the request's (or
 * {@link SimulationOptions#DEFAULT_MAX_DATUM}), up to {@code maxDatum}. Its
 * timeout grows with it, {@link #TIMEOUT_PER_THOUSAND_DATUM} per 1,000 datum
 * (at least that), up to {@code maxRunTime}, and so does the number of
 * scheduled task runs, {@link #TASK_RUNS_PER_DATUM} per datum (at least
 * {@link #MIN_TASK_RUNS}).
 * </p>
 *
 * @param maxDatum
 *        the most datum a run may process
 * @param maxRunTime
 *        the longest a run may take
 * @author matt
 * @version 1.0
 */
public record SimulationLimits(int maxDatum, Duration maxRunTime) {

	/** The default {@code maxDatum} value. */
	public static final int DEFAULT_MAX_DATUM = 100_000;

	/** The default {@code maxRunTime} value. */
	public static final Duration DEFAULT_MAX_RUN_TIME = Duration.ofMinutes(10);

	/** The default limits. */
	public static final SimulationLimits DEFAULTS = new SimulationLimits(DEFAULT_MAX_DATUM,
			DEFAULT_MAX_RUN_TIME);

	/** The run time allowed for each 1,000 datum. */
	public static final Duration TIMEOUT_PER_THOUSAND_DATUM = Duration
			.ofMillis(SimulationOptions.DEFAULT_TIMEOUT_MS);

	/** The scheduled task runs allowed for each datum. */
	public static final int TASK_RUNS_PER_DATUM = 10;

	/** The fewest scheduled task runs allowed. */
	public static final int MIN_TASK_RUNS = 10_000;

	/**
	 * The limits of one run.
	 *
	 * @param maxDatum
	 *        the most datum the run may process
	 * @param timeoutMs
	 *        the longest the run may take, in milliseconds
	 * @param maxTaskRuns
	 *        the most scheduled tasks and stored instructions the run may run
	 * @param requestedMaxDatum
	 *        the Maximum Datum the request asked for, which is more than
	 *        {@code maxDatum} when the server limited it
	 */
	public record RunLimits(int maxDatum, long timeoutMs, int maxTaskRuns, int requestedMaxDatum) {

		/**
		 * Test if the server limited the request's Maximum Datum.
		 *
		 * @return {@code true} if {@code maxDatum} is less than the request's
		 */
		public boolean datumLimited() {
			return requestedMaxDatum > maxDatum;
		}

	}

	/**
	 * Constructor.
	 *
	 * @param maxDatum
	 *        the most datum a run may process; less than 1 is
	 *        {@link #DEFAULT_MAX_DATUM}
	 * @param maxRunTime
	 *        the longest a run may take; not positive is
	 *        {@link #DEFAULT_MAX_RUN_TIME}
	 */
	public SimulationLimits {
		if ( maxDatum < 1 ) {
			maxDatum = DEFAULT_MAX_DATUM;
		}
		if ( maxRunTime.isNegative() || maxRunTime.isZero() ) {
			maxRunTime = DEFAULT_MAX_RUN_TIME;
		}
	}

	/**
	 * Get the limits of a run.
	 *
	 * @param options
	 *        the run's options
	 * @return the limits
	 */
	public RunLimits runLimits(SimulationOptions options) {
		final int requested = options.maxDatumValue();
		final int datum = Math.min(requested, maxDatum);
		final long maxRunTimeMs = maxRunTime.toMillis();
		final long timeoutMs;
		if ( options.timeoutMs() != null && options.timeoutMs() > 0 ) {
			timeoutMs = Math.min(options.timeoutMs(), maxRunTimeMs);
		} else {
			final long thousands = Math.ceilDiv(datum, 1000);
			timeoutMs = Math.min(Math.max(1, thousands) * TIMEOUT_PER_THOUSAND_DATUM.toMillis(),
					maxRunTimeMs);
		}
		final int taskRuns = (int) Math.min(Integer.MAX_VALUE,
				Math.max(MIN_TASK_RUNS, (long) datum * TASK_RUNS_PER_DATUM));
		return new RunLimits(datum, timeoutMs, taskRuns, requested);
	}

}
