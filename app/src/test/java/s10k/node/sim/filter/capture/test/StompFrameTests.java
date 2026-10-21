/* ==================================================================
 * StompFrameTests.java - 21 Oct 2026 2:30:00 pm
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
import static org.assertj.core.api.BDDAssertions.thenIllegalArgumentException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.capture.StompFrame;

/**
 * Test cases for the {@link StompFrame} class.
 *
 * @author matt
 * @version 1.0
 */
public class StompFrameTests {

	@Test
	public void encode() {
		// GIVEN
		Map<String, String> headers = new LinkedHashMap<>();
		headers.put("id", "0");
		headers.put("destination", "/topic/a:b");
		StompFrame frame = StompFrame.of(StompFrame.SUBSCRIBE, headers);

		// WHEN
		String result = frame.encode();

		// THEN
		// @formatter:off
		then(result)
			.as("Command, escaped headers, blank line, empty body, NUL")
			.isEqualTo("SUBSCRIBE\nid:0\ndestination:/topic/a\\cb\n\n\0")
			;
		// @formatter:on
	}

	@Test
	public void encode_connect() {
		// GIVEN
		StompFrame frame = StompFrame.of(StompFrame.CONNECT, Map.of("host", "node:8080"));

		// WHEN
		String result = frame.encode();

		// THEN
		// @formatter:off
		then(result)
			.as("CONNECT headers are not escaped")
			.isEqualTo("CONNECT\nhost:node:8080\n\n\0")
			;
		// @formatter:on
	}

	@Test
	public void parse_message() {
		// GIVEN
		String text = "MESSAGE\r\ndestination:/topic/datum/captured/meter/1\r\n"
				+ "subscription:0\r\nmessage-id:a\\cb\r\nmessage-id:second\r\ncontent-length:17\r\n\r\n"
				+ "{\"success\":true}\0";

		// WHEN
		StompFrame result = StompFrame.parse(text);

		// THEN
		// @formatter:off
		then(result)
			.as("Frame parsed")
			.isNotNull()
			;
		then(result.command())
			.as("Command")
			.isEqualTo(StompFrame.MESSAGE)
			;
		then(result.headers())
			.as("Headers unescaped, first of a repeated header")
			.containsExactly(
					Map.entry("destination", "/topic/datum/captured/meter/1"),
					Map.entry("subscription", "0"),
					Map.entry("message-id", "a:b"),
					Map.entry("content-length", "17"))
			;
		then(result.body())
			.as("Body up to NUL")
			.isEqualTo("{\"success\":true}")
			;
		// @formatter:on
	}

	@Test
	public void parse_heartBeat() {
		// THEN
		// @formatter:off
		then(StompFrame.parse("\n"))
			.as("A heart-beat is not a frame")
			.isNull()
			;
		// @formatter:on
	}

	@Test
	public void parse_heartBeatBeforeFrame() {
		// WHEN
		StompFrame result = StompFrame.parse("\n\nCONNECTED\nversion:1.2\n\n\0");

		// THEN
		// @formatter:off
		then(result)
			.as("Frame after heart-beats")
			.isNotNull()
			.returns(StompFrame.CONNECTED, StompFrame::command)
			.returns("1.2", f -> f.header("version"))
			;
		// @formatter:on
	}

	@Test
	public void parse_noHeadersEnd() {
		// THEN
		// @formatter:off
		thenIllegalArgumentException()
			.as("A frame needs a blank line after its headers")
			.isThrownBy(() -> StompFrame.parse("MESSAGE\nid:1"))
			;
		// @formatter:on
	}

}
