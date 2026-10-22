/* ==================================================================
 * BrowserLauncherTests.java - 23 Oct 2026 10:30:00 am
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

package s10k.node.sim.filter.test;

import static org.assertj.core.api.BDDAssertions.then;
import java.util.Map;
import org.junit.jupiter.api.Test;
import s10k.node.sim.filter.BrowserLauncher;

/**
 * Test cases for the {@link BrowserLauncher} class.
 *
 * @author matt
 * @version 1.0
 */
public class BrowserLauncherTests {

	private static final String URL = "http://localhost:2008";

	@Test
	public void command_mac() {
		then(BrowserLauncher.command("Mac OS X", Map.of(), URL)).as("Uses open")
				.containsExactly("open", URL);
	}

	@Test
	public void command_windows() {
		then(BrowserLauncher.command("Windows 11", Map.of(), URL)).as("Uses the URL handler")
				.containsExactly("rundll32", "url.dll,FileProtocolHandler", URL);
	}

	@Test
	public void command_linux_x11() {
		then(BrowserLauncher.command("Linux", Map.of("DISPLAY", ":0"), URL)).as("Uses xdg-open")
				.containsExactly("xdg-open", URL);
	}

	@Test
	public void command_linux_wayland() {
		then(BrowserLauncher.command("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0"), URL))
				.as("Uses xdg-open").containsExactly("xdg-open", URL);
	}

	@Test
	public void command_linux_headless() {
		then(BrowserLauncher.command("Linux", Map.of("DISPLAY", " "), URL))
				.as("No command without a display").isNull();
	}

}
