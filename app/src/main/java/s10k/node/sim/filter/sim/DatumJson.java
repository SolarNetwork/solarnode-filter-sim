/* ==================================================================
 * DatumJson.java - 18 Oct 2026 8:56:20 am
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

import java.time.Instant;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.codec.jackson.JsonUtils;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.domain.datum.DatumSamples;
import net.solarnetwork.domain.datum.ObjectDatumKind;
import net.solarnetwork.node.domain.datum.NodeDatum;
import net.solarnetwork.node.domain.datum.SimpleDatum;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Datum JSON support, using the SolarNetwork datum JSON codec.
 *
 * <p>
 * Both the "direct" style, like
 * {@code {"created":"2025-10-01 00:00:00Z","sourceId":"/meter/1","i":{"watts":1}}},
 * and the "nested" style, with properties in a {@code samples} object, are
 * supported.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class DatumJson {

	private static final JsonMapper MAPPER = JsonUtils.JSON_OBJECT_MAPPER.rebuild()
			.addModule(JsonUtils.DATUM_MODULE).build();

	private DatumJson() {
		// not available
	}

	/**
	 * Parse a datum.
	 *
	 * @param json
	 *        the datum JSON
	 * @param label
	 *        a description of the datum for error messages, like
	 *        {@code "Datum 1"}
	 * @return the datum
	 * @throws SimulationException
	 *         if the JSON is not a valid datum
	 */
	public static Datum parse(@Nullable JsonNode json, String label) {
		if ( json == null || !json.isObject() ) {
			throw new SimulationException(label + " is not a JSON object.");
		}
		final Datum d;
		try {
			d = MAPPER.treeToValue(json, Datum.class);
		} catch ( JacksonException e ) {
			throw new SimulationException(label + " is not a valid datum: " + e.getOriginalMessage(), e);
		}
		if ( d == null ) {
			throw new SimulationException(label + " is not a valid datum.");
		}
		if ( d.getSourceId() == null || d.getSourceId().isBlank() ) {
			throw new SimulationException(label + " has no sourceId.");
		}
		return d;
	}

	/**
	 * Create a SolarNode datum from a datum.
	 *
	 * @param datum
	 *        the datum
	 * @param timestamp
	 *        the timestamp to use if the datum has none
	 * @return the SolarNode datum
	 */
	public static NodeDatum nodeDatum(Datum datum, Instant timestamp) {
		final Instant datumTimestamp = datum.getTimestamp();
		final Instant ts = (datumTimestamp != null ? datumTimestamp : timestamp);
		final DatumSamples samples = new DatumSamples(datum.asSampleOperations());
		final Long locationId = datum.getObjectId();
		if ( datum.getKind() == ObjectDatumKind.Location && locationId != null ) {
			return SimpleDatum.locationDatum(locationId, datum.getSourceId(), ts, samples);
		}
		return SimpleDatum.nodeDatum(datum.getSourceId(), ts, samples);
	}

}
