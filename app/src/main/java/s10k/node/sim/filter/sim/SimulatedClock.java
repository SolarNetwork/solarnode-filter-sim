/* ==================================================================
 * SimulatedClock.java - 18 Oct 2026 8:56:20 am
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

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * A clock whose time the simulation sets, to the timestamp of the datum being
 * processed.
 *
 * <p>
 * Clocks derived from this one, with {@link #withZone(ZoneId)} or with methods
 * like {@link Clock#tick(Clock, java.time.Duration)}, follow its time.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class SimulatedClock extends Clock {

	private volatile Instant instant;

	/**
	 * Constructor.
	 *
	 * @param instant
	 *        the initial time
	 */
	public SimulatedClock(Instant instant) {
		super();
		this.instant = instant;
	}

	/**
	 * Set the time.
	 *
	 * @param instant
	 *        the time to set
	 */
	public void setInstant(Instant instant) {
		this.instant = instant;
	}

	@Override
	public Instant instant() {
		return instant;
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		if ( ZoneOffset.UTC.equals(zone) ) {
			return this;
		}
		return new ZonedView(zone);
	}

	private final class ZonedView extends Clock {

		private final ZoneId zone;

		private ZonedView(ZoneId zone) {
			super();
			this.zone = zone;
		}

		@Override
		public Instant instant() {
			return instant;
		}

		@Override
		public ZoneId getZone() {
			return zone;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return SimulatedClock.this.withZone(zone);
		}

	}

}
