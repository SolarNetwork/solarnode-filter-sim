/* ==================================================================
 * DatumMessages.java - 21 Oct 2026 2:30:00 pm
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

package s10k.node.sim.filter.capture;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import net.solarnetwork.domain.datum.Datum;
import net.solarnetwork.node.service.DatumEvents;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Datum messages from a SolarNode's WebSocket, as simulation input datum.
 *
 * <p>
 * A node posts each datum its Datum Queue receives, before the filters run, to
 * the {@code /topic/datum/captured/<sourceId>} topic, as a
 * {@code {"success":true,"data":{…}}} result. The data has the datum's
 * properties as top-level properties, which do not say which sample type each
 * property has. From {@code net.solarnetwork.node} 4.10.1 the data also has the
 * datum itself, as {@link #DATUM_PROPERTY}, in the SolarNode datum JSON format
 * that simulations use. For older nodes the sample types are guessed:
 * </p>
 *
 * <ul>
 * <li>{@code tags} are tags;</li>
 * <li>numbers are instantaneous, except the energy readings of the standard
 * datum types ({@code wattHours} and {@code wattHoursReverse}, also with a
 * phase suffix like {@code _a}), which are accumulating;</li>
 * <li>everything else is status.</li>
 * </ul>
 *
 * <p>
 * Numbers keep their exact text.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class DatumMessages {

	/**
	 * The message property with the datum, in the SolarNode datum JSON format.
	 */
	public static final String DATUM_PROPERTY = DatumEvents.DATUM_PROPERTY;

	/** The message property with the source ID. */
	public static final String SOURCE_ID_PROPERTY = Datum.SOURCE_ID;

	/** The message property with the datum's date. */
	public static final String CREATED_PROPERTY = Datum.TIMESTAMP;

	/** The message property with the datum's tags. */
	public static final String TAGS_PROPERTY = "tags";

	/** The top-level message properties that are not datum properties. */
	private static final Set<String> MESSAGE_PROPERTIES = Set.of(DATUM_PROPERTY,
			Datum.DATUM_TYPE_PROPERTY, Datum.DATUM_TYPES_PROPERTY, CREATED_PROPERTY, "event.topics",
			SOURCE_ID_PROPERTY);

	/**
	 * The properties of a datum in the SolarNode datum JSON format, in order.
	 */
	private static final List<String> DATUM_PROPERTIES = List.of(CREATED_PROPERTY, "nodeId",
			"locationId", SOURCE_ID_PROPERTY, "i", "a", "s", "t");

	/** The accumulating properties of the standard SolarNode datum types. */
	private static final Pattern ACCUMULATING_PROPERTY = Pattern.compile("wattHours(Reverse)?(_[abc])?");

	/** The format of datum dates, the same as SolarNode's datum JSON. */
	private static final DateTimeFormatter CREATED_FORMAT = DateTimeFormatter
			.ofPattern("yyyy-MM-dd HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

	// keep the exact text of decimal numbers
	private static final JsonMapper MAPPER = JsonMapper.builder()
			.enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
			.disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES).build();

	private DatumMessages() {
		// not available
	}

	/**
	 * A datum from a message.
	 *
	 * @param datum
	 *        the datum, in the SolarNode datum JSON format
	 * @param guessed
	 *        {@code true} if the sample type of each property was guessed,
	 *        because the message did not include the datum itself
	 */
	public record MessageDatum(ObjectNode datum, boolean guessed) {

		/**
		 * Get the datum JSON.
		 *
		 * @return the JSON, on one line
		 */
		public String json() {
			return MAPPER.writeValueAsString(datum);
		}

	}

	/**
	 * Get the datum of a message.
	 *
	 * @param body
	 *        the message body, the JSON of a {@code Result} with the datum
	 *        properties as its data
	 * @return the datum, or {@code null} if the message has no datum with a
	 *         source ID
	 */
	public static @Nullable MessageDatum datum(String body) {
		final JsonNode root;
		try {
			root = MAPPER.readTree(body);
		} catch ( JacksonException e ) {
			return null;
		}
		final JsonNode data = (root != null ? root.path("data") : null);
		if ( data == null || !data.isObject() || !hasText(data.path(SOURCE_ID_PROPERTY)) ) {
			return null;
		}
		final JsonNode datum = data.path(DATUM_PROPERTY);
		if ( datum.isObject() && hasText(datum.path(SOURCE_ID_PROPERTY)) ) {
			return new MessageDatum(copyDatum(datum), false);
		}
		return new MessageDatum(guessDatum(data), true);
	}

	private static boolean hasText(JsonNode node) {
		return node.isString() && !node.asString().isBlank();
	}

	/**
	 * Copy the datum properties of a datum in the SolarNode datum JSON format.
	 */
	private static ObjectNode copyDatum(JsonNode datum) {
		final ObjectNode result = MAPPER.createObjectNode();
		for ( String prop : DATUM_PROPERTIES ) {
			JsonNode value = datum.get(prop);
			if ( value != null && !value.isNull() ) {
				result.set(prop, CREATED_PROPERTY.equals(prop) ? created(value) : value);
			}
		}
		return result;
	}

	/**
	 * Create a datum from the top-level message properties, guessing the sample
	 * type of each property.
	 */
	private static ObjectNode guessDatum(JsonNode data) {
		final ObjectNode result = MAPPER.createObjectNode();
		final JsonNode created = data.get(CREATED_PROPERTY);
		if ( created != null && !created.isNull() ) {
			result.set(CREATED_PROPERTY, created(created));
		}
		result.set(SOURCE_ID_PROPERTY, data.get(SOURCE_ID_PROPERTY));
		final ObjectNode i = MAPPER.createObjectNode();
		final ObjectNode a = MAPPER.createObjectNode();
		final ObjectNode s = MAPPER.createObjectNode();
		final ArrayNode t = MAPPER.createArrayNode();
		for ( Map.Entry<String, JsonNode> e : data.properties() ) {
			final String prop = e.getKey();
			final JsonNode value = e.getValue();
			if ( MESSAGE_PROPERTIES.contains(prop) || value.isNull() ) {
				continue;
			}
			if ( TAGS_PROPERTY.equals(prop) && value.isArray() ) {
				for ( JsonNode tag : value ) {
					if ( hasText(tag) ) {
						t.add(tag);
					}
				}
			} else if ( value.isNumber() ) {
				(ACCUMULATING_PROPERTY.matcher(prop).matches() ? a : i).set(prop, value);
			} else {
				s.set(prop, value);
			}
		}
		if ( !i.isEmpty() ) {
			result.set("i", i);
		}
		if ( !a.isEmpty() ) {
			result.set("a", a);
		}
		if ( !s.isEmpty() ) {
			result.set("s", s);
		}
		if ( !t.isEmpty() ) {
			result.set("t", t);
		}
		return result;
	}

	/**
	 * Get a datum date: epoch milliseconds as a date like
	 * {@code 2026-10-21 03:15:42.123Z}, and anything else as it is.
	 */
	private static JsonNode created(JsonNode value) {
		if ( value.isIntegralNumber() && value.canConvertToLong() ) {
			return MAPPER.getNodeFactory()
					.stringNode(CREATED_FORMAT.format(Instant.ofEpochMilli(value.longValue())));
		}
		return value;
	}

}
