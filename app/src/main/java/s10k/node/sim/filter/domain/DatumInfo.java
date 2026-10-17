/* ==================================================================
 * DatumInfo.java - 18 Oct 2026 8:56:20 am
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

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import com.fasterxml.jackson.annotation.JsonInclude;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.ObjectDatumKind;

/**
 * A snapshot of a datum.
 *
 * @param sourceId
 *        the source ID
 * @param timestamp
 *        the timestamp
 * @param locationId
 *        the location ID, for a location datum, or {@code null}
 * @param samples
 *        the sample properties
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DatumInfo(@Nullable String sourceId, @Nullable Instant timestamp,
		@Nullable Long locationId, SamplesInfo samples) {

	/**
	 * Create a snapshot of a datum.
	 *
	 * @param datum
	 *        the datum to copy
	 * @return the snapshot
	 */
	public static DatumInfo from(Datum datum) {
		return new DatumInfo(datum.getSourceId(), datum.getTimestamp(),
				datum.getKind() == ObjectDatumKind.Location ? datum.getObjectId() : null,
				SamplesInfo.from(datum.asSampleOperations()));
	}

}
