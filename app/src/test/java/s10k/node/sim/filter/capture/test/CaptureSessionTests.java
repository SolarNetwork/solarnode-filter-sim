/* ==================================================================
 * CaptureSessionTests.java - 22 Oct 2026 10:47:36 am
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
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.capture.CaptureService;
import s10k.node.sim.filter.capture.CaptureSession;
import s10k.node.sim.filter.capture.StompFrame;

/**
 * Test cases for the {@link CaptureSession} class.
 *
 * @author matt
 * @version 1.0
 */
public class CaptureSessionTests {

	private static final URI URL = URI.create("http://solarnode.local/");

	private static StompFrame message(String subscription, String sourceId, int watts) {
		String body = """
				{"success":true,"data":{"created":1760000000123,"sourceId":"%s","watts":%d,\
				"_Datum":{"created":"2025-10-09 08:53:20.123Z","sourceId":"%s","i":{"watts":%d}}}}"""
				.formatted(sourceId, watts, sourceId, watts);
		return new StompFrame(StompFrame.MESSAGE, Map.of("subscription", subscription), body);
	}

	@Test
	public void repeatsFromOtherSubscriptionsDropped() {
		// GIVEN
		CaptureSession session = new CaptureSession("test", URL, List
				.of(CaptureService.TOPIC_PREFIX + "meter/**", CaptureService.TOPIC_PREFIX + "meter/1"));

		// WHEN
		session.message(message("0", "/meter/1", 1));
		session.message(message("1", "/meter/1", 1));
		session.message(message("0", "/meter/2", 2));
		session.message(message("0", "/meter/1", 1));
		session.message(message("1", "/meter/1", 1));

		// THEN
		// @formatter:off
		then(session.count())
			.as("Repeats from the other subscription dropped, same datum again from the same one kept")
			.isEqualTo(3)
			;
		// @formatter:on
	}

	@Test
	public void sameDatumOneSubscription() {
		// GIVEN
		CaptureSession session = new CaptureSession("test", URL,
				List.of(CaptureService.TOPIC_PREFIX + "**"));

		// WHEN
		session.message(message("0", "/meter/1", 1));
		session.message(message("0", "/meter/1", 1));

		// THEN
		// @formatter:off
		then(session.count())
			.as("With one subscription, every message is a datum")
			.isEqualTo(2)
			;
		// @formatter:on
	}

}
