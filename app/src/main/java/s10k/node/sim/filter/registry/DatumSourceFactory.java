/* ==================================================================
 * DatumSourceFactory.java - 29 Oct 2026 6:51:00 pm
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

package s10k.node.sim.filter.registry;

import java.util.random.RandomGenerator;
import net.solarnetwork.node.service.DatumSourceIdProvider;

/**
 * API for creating a datum source instance, the way the SolarNode blueprint
 * wiring creates it.
 *
 * @author matt
 * @version 1.1
 */
@FunctionalInterface
public interface DatumSourceFactory {

	/**
	 * Create a new datum source instance.
	 *
	 * @param context
	 *        the simulated services
	 * @param rng
	 *        a random number generator seeded for this datum source, so a
	 *        simulation gives the same datum each time it runs
	 * @return the new datum source, a {@code DatumDataSource}, or a
	 *         {@code MultiDatumDataSource} for a multi datum source type
	 */
	DatumSourceIdProvider create(DatumSourceContext context, RandomGenerator rng);

}
