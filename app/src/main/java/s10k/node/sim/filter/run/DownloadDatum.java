/* ==================================================================
 * DownloadDatum.java - 29 Oct 2026 9:00:00 pm
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.domain.DatumInfo;
import s10k.node.sim.filter.domain.SamplesInfo;

/**
 * A datum in the format of the simulator's Datum input, which is SolarNode's
 * datum JSON, for downloads.
 *
 * @param created
 *        the date, like {@code 2026-10-29 08:00:00.000Z}
 * @param sourceId
 *        the source ID
 * @param locationId
 *        the location ID of a location datum
 * @param i
 *        the instantaneous properties
 * @param a
 *        the accumulating properties
 * @param s
 *        the status properties
 * @param t
 *        the tags
 * @author matt
 * @version 1.0
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
@JsonPropertyOrder({ "created", "sourceId", "locationId", "i", "a", "s", "t" })
public record DownloadDatum(@Nullable String created, @Nullable String sourceId,
		@Nullable Long locationId, @Nullable Map<String, Object> i, @Nullable Map<String, Object> a,
		@Nullable Map<String, Object> s, @Nullable Set<String> t) {

	/** The format of datum dates, the same as SolarNode's datum JSON. */
	public static final DateTimeFormatter CREATED_FORMAT = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	/**
	 * Get a datum for download.
	 *
	 * @param datum
	 *        the datum
	 * @return the datum to download
	 */
	public static DownloadDatum of(DatumInfo datum) {
		final Instant ts = datum.timestamp();
		final SamplesInfo samples = datum.samples();
		return new DownloadDatum(ts != null ? CREATED_FORMAT.format(ts) : null, datum.sourceId(),
				datum.locationId(), samples.i(), samples.a(), samples.s(), samples.t());
	}

}
