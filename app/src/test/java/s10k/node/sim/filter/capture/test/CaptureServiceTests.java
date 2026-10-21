/* ==================================================================
 * CaptureServiceTests.java - 21 Oct 2026 2:30:00 pm
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
import static org.assertj.core.api.BDDAssertions.thenExceptionOfType;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import s10k.node.sim.filter.capture.CaptureException;
import s10k.node.sim.filter.capture.CaptureService;

/**
 * Test cases for the {@link CaptureService} class.
 *
 * @author matt
 * @version 1.0
 */
public class CaptureServiceTests {

	@ParameterizedTest
	@CsvSource({
	// @formatter:off
		"solarnode.local,                       http://solarnode.local/",
		"192.168.1.10:8080,                     http://192.168.1.10:8080/",
		"http://solarnode.local,                http://solarnode.local/",
		"HTTPS://Node.Example.com/,             https://Node.Example.com/",
		"http://solarnode.local/a/settings,     http://solarnode.local/",
		"http://solarnode.local/login.do?x=1,   http://solarnode.local/",
		"http://solarnode.local/sn/a/home#top,  http://solarnode.local/sn/",
		"http://solarnode.local/sn,             http://solarnode.local/sn/",
		// @formatter:on
	})
	public void nodeUrl(String url, String expected) {
		// WHEN
		URI result = CaptureService.nodeUrl(url);

		// THEN
		// @formatter:off
		then(result)
			.as("Node web address from %s", url)
			.hasToString(expected)
			;
		// @formatter:on
	}

	@ParameterizedTest
	@ValueSource(strings = { "", " ", "ftp://solarnode.local", "http://", "http://a b" })
	public void nodeUrl_invalid(String url) {
		// THEN
		// @formatter:off
		thenExceptionOfType(CaptureException.class)
			.as("Not a node web address: [%s]", url)
			.isThrownBy(() -> CaptureService.nodeUrl(url))
			;
		// @formatter:on
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', nullValues = "null", value = {
	// @formatter:off
		"null                        | **",
		"''                          | **",
		"' , '                       | **",
		"/meter/**                   | meter/**",
		"meter1                      | meter1",
		"//a/*                       | a/*",
		"/meter/*, /inv/*            | meter/*, inv/*",
		"/meter/*,/meter/*, inv/1    | meter/*, inv/1",
		"/meter/*, **                | **",
		"/meter/*, /**               | **",
		// @formatter:on
	})
	public void topics(String sourceIds, String expected) {
		// WHEN
		List<String> result = CaptureService.topics(sourceIds);

		// THEN
		// @formatter:off
		then(result)
			.as("Topics for source IDs %s, without their leading slash or repeats", sourceIds)
			.containsExactlyElementsOf(Arrays.stream(expected.split(","))
					.map(p -> CaptureService.TOPIC_PREFIX + p.strip())
					.toList())
			;
		// @formatter:on
	}

}
