/* ==================================================================
 * CaptureRequest.java - 21 Oct 2026 2:30:00 pm
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

import org.jspecify.annotations.Nullable;

/**
 * A request to capture the datum a SolarNode captures, as input datum for a
 * simulation.
 *
 * @param url
 *        the node's web address, like {@code http://solarnode.local}
 * @param username
 *        the username to log in to the node with
 * @param password
 *        the password to log in to the node with
 * @param sourceIds
 *        a comma-delimited list of Ant-style patterns of the source IDs to
 *        capture, like {@code /meter/**, /inv/*}, or {@code null} for every
 *        source
 * @author matt
 * @version 1.0
 */
public record CaptureRequest(@Nullable String url, @Nullable String username, @Nullable String password,
		@Nullable String sourceIds) {

	@Override
	public String toString() {
		// never the password
		return "CaptureRequest{url=" + url + ", username=" + username + ", sourceIds=" + sourceIds + "}";
	}

}
