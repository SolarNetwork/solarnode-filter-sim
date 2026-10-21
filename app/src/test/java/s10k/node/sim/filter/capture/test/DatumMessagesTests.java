/* ==================================================================
 * DatumMessagesTests.java - 21 Oct 2026 2:30:00 pm
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

package s10k.node.sim.filter.capture.test;

import static org.assertj.core.api.BDDAssertions.then;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.capture.DatumMessages;
import s10k.node.sim.filter.capture.DatumMessages.MessageDatum;

/**
 * Test cases for the {@link DatumMessages} class.
 *
 * @author matt
 * @version 1.0
 */
public class DatumMessagesTests {

	/** The flattened properties every datum message has. */
	private static final String FLAT = """
			"created":1760000000123,"sourceId":"/meter/1",\
			"_DatumType":"net.solarnetwork.domain.datum.AcEnergyDatum",\
			"_DatumTypes":["net.solarnetwork.domain.datum.AcEnergyDatum"],\
			"event.topics":"net/solarnetwork/node/DatumDataSource/DATUM_CAPTURED",\
			"watts":1234,"voltage":240.10,"wattHours":98765432.123456789,"status":"ok",\
			"tags":["power"]""";

	@Test
	public void datum() {
		// GIVEN
		String body = """
				{"success":true,"data":{%s,"_Datum":{"created":"2025-10-09 08:53:20.123Z",\
				"sourceId":"/meter/1","i":{"watts":1234,"voltage":240.10},\
				"a":{"wattHours":98765432.123456789,"gasVolume":12},"s":{"status":"ok"},\
				"t":["power"]}}}""".formatted(FLAT);

		// WHEN
		MessageDatum result = DatumMessages.datum(body);

		// THEN
		// @formatter:off
		then(result)
			.as("Datum from message")
			.isNotNull()
			;
		then(result.guessed())
			.as("Sample types from the datum, not guessed")
			.isFalse()
			;
		then(result.json())
			.as("Datum as the node sent it, with exact numbers")
			.isEqualTo("""
					{"created":"2025-10-09 08:53:20.123Z","sourceId":"/meter/1",\
					"i":{"watts":1234,"voltage":240.10},\
					"a":{"wattHours":98765432.123456789,"gasVolume":12},\
					"s":{"status":"ok"},"t":["power"]}""")
			;
		// @formatter:on
	}

	@Test
	public void guessed() {
		// GIVEN
		String body = """
				{"success":true,"data":{%s,"wattHoursReverse_a":5,"phase":"PhaseA",\
				"flags":[1,2]}}""".formatted(FLAT);

		// WHEN
		MessageDatum result = DatumMessages.datum(body);

		// THEN
		// @formatter:off
		then(result)
			.as("Datum from message")
			.isNotNull()
			;
		then(result.guessed())
			.as("Sample types guessed")
			.isTrue()
			;
		then(result.json())
			.as("Numbers instantaneous except energy readings, others status, tags as tags")
			.isEqualTo("""
					{"created":"2025-10-09 08:53:20.123Z","sourceId":"/meter/1",\
					"i":{"watts":1234,"voltage":240.10},\
					"a":{"wattHours":98765432.123456789,"wattHoursReverse_a":5},\
					"s":{"status":"ok","phase":"PhaseA","flags":[1,2]},"t":["power"]}""")
			;
		// @formatter:on
	}

	@Test
	public void noSourceId() {
		// WHEN
		MessageDatum result = DatumMessages.datum("""
				{"success":true,"data":{"created":1760000000123,"watts":1}}""");

		// THEN
		// @formatter:off
		then(result)
			.as("No datum without a source ID")
			.isNull()
			;
		// @formatter:on
	}

	@Test
	public void notJson() {
		// WHEN
		MessageDatum result = DatumMessages.datum("not JSON");

		// THEN
		// @formatter:off
		then(result)
			.as("No datum from invalid JSON")
			.isNull()
			;
		// @formatter:on
	}

}
