/* ==================================================================
 * InterfaceInitializer.java - 19 Oct 2026 2:48:30 pm
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

package s10k.node.sim.filter.aot;

import java.util.List;
import net.solarnetwork.dao.GenericDao;
import net.solarnetwork.domain.GroupedBitmaskable;
import net.solarnetwork.domain.LocalizedServiceInfo;
import net.solarnetwork.domain.datum.DatumSamplesOperations;
import net.solarnetwork.domain.datum.DatumStringFunctions;
import net.solarnetwork.domain.datum.StreamDatum;
import net.solarnetwork.io.ResourceMetadata;

/**
 * Initialize the SolarNetwork interfaces whose default methods read their own
 * constants, for the native image.
 *
 * <p>
 * GraalVM's class initializer simulation initializes classes such as
 * {@code DatumSamples} and {@code SimpleDatum} at build time, but leaves the
 * interfaces they implement, such as {@link DatumSamplesOperations}, for run
 * time. Nothing then initializes those interfaces, because the native image
 * drops the initialization check when an interface's own default method reads
 * its constant. The constant stays {@code null}, so for example the Unchanged
 * filter and the {@code regexReplace()} expression function fail with a
 * {@code NullPointerException}. Reading a constant from another class keeps the
 * check, so it initializes the interface.
 * </p>
 *
 * <p>
 * The interfaces are those in the SolarNetwork jars with a static initializer
 * and default methods, apart from {@code ServiceConfiguration}, whose
 * initializer sets no constant. Re-check them when the bundles are upgraded;
 * the {@code nativeSmokeTest} fixtures catch this kind of failure. Initializing
 * them at build time instead is not possible, because
 * {@link DatumStringFunctions#PATTERN_CACHE} is a Spring
 * {@code ConcurrentLruCache}, which must be initialized at run time.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class InterfaceInitializer {

	private InterfaceInitializer() {
		// not available
	}

	/**
	 * Initialize the interfaces.
	 *
	 * @return the number of interfaces initialized
	 * @throws NullPointerException
	 *         if a constant is still {@code null}
	 */
	public static int initialize() {
		// @formatter:off
		return List.of(
				GenericDao.SORT_BY_CREATED_ASCENDING,
				GroupedBitmaskable.SORT_BY_OVERALL_INDEX,
				LocalizedServiceInfo.SORT_BY_NAME,
				DatumSamplesOperations.KEYED_TYPES,
				DatumStringFunctions.PATTERN_CACHE,
				StreamDatum.UNASSIGNED_STREAM_ID,
				ResourceMetadata.DEFAULT_CONTENT_TYPE).size();
		// @formatter:on
	}

}
