/* ==================================================================
 * InstanceIdsTests.java - 18 Oct 2026 1:03:29 pm
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

package s10k.node.sim.filter.csv.test;

import static org.assertj.core.api.BDDAssertions.then;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import s10k.node.sim.filter.csv.InstanceIds;

/**
 * Test cases for the {@link InstanceIds} class.
 *
 * @author matt
 * @version 1.0
 */
public class InstanceIdsTests {

	@ParameterizedTest
	@ValueSource(strings = { "Main", "1", "Peak Power", "a-b_c/d", "Ünïcödé", "日本語",
			"abcdefghijklmnopqrstuvwxyz012345" })
	public void allowed(String id) {
		// WHEN
		String result = InstanceIds.problem(id);

		// THEN
		// @formatter:off
		then(result)
			.as("Instance ID %s allowed", id)
			.isNull()
			;
		// @formatter:on
	}

	/**
	 * Instance IDs that are not allowed. The {@code ½} passes the setup UI
	 * pattern but not the server pattern, and the Devanagari vowel sign in
	 * {@code कि} passes the server pattern but not the setup UI pattern.
	 */
	@ParameterizedTest
	@ValueSource(strings = { "", "abcdefghijklmnopqrstuvwxyz0123456", "FACTORY", "a.b", "a:b", " lead",
			"trail ", "½", "कि" })
	public void notAllowed(String id) {
		// WHEN
		String result = InstanceIds.problem(id);

		// THEN
		// @formatter:off
		then(result)
			.as("Instance ID [%s] not allowed", id)
			.isNotNull()
			;
		// @formatter:on
	}

	@Test
	public void next() {
		// THEN
		// @formatter:off
		then(InstanceIds.next(List.of()))
			.as("First instance")
			.isEqualTo("1")
			;
		then(InstanceIds.next(List.of("1")))
			.as("Next number")
			.isEqualTo("2")
			;
		then(InstanceIds.next(List.of("Main")))
			.as("Counts named instances, like SolarNode")
			.isEqualTo("2")
			;
		then(InstanceIds.next(List.of("2")))
			.as("Skips numbers in use")
			.isEqualTo("3")
			;
		// @formatter:on
	}

	@Test
	public void legacyCompatible() {
		// THEN
		// @formatter:off
		then(List.of("Main", "15Min", "1"))
			.as("ASCII letters and digits")
			.allMatch(InstanceIds::isLegacyCompatible)
			;
		then(List.of("Peak Power", "a-b", "Ünïcödé"))
			.as("Other characters")
			.noneMatch(InstanceIds::isLegacyCompatible)
			;
		// @formatter:on
	}

}
