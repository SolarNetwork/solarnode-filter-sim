/* ==================================================================
 * BrowserLauncher.java - 23 Oct 2026 10:15:00 am
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

package s10k.node.sim.filter;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Open a URL in the user's preferred web browser, with the operating system's
 * own command for that.
 *
 * <p>
 * This runs {@code open} on macOS, {@code rundll32 url.dll,FileProtocolHandler}
 * on Windows, and {@code xdg-open} on other systems, rather than
 * {@code java.awt.Desktop}, which would bring AWT into the native executable.
 * On other systems the browser is opened only when there is a graphical
 * display, from the {@code DISPLAY} or {@code WAYLAND_DISPLAY} environment
 * variables, so nothing is attempted on a headless machine.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class BrowserLauncher {

	private static final Logger log = LoggerFactory.getLogger(BrowserLauncher.class);

	private BrowserLauncher() {
		// not available
	}

	/**
	 * Open a URL in the user's preferred web browser, if possible.
	 *
	 * <p>
	 * A failure to open the browser is logged, not thrown, as the URL is also
	 * printed for the user to open themselves.
	 * </p>
	 *
	 * @param url
	 *        the URL to open
	 * @return {@code true} if the command to open the browser was started
	 */
	public static boolean open(String url) {
		final List<String> cmd = command(System.getProperty("os.name", ""), System.getenv(), url);
		if ( cmd == null ) {
			log.info("Not opening a web browser, as no graphical display is available");
			return false;
		}
		try {
			new ProcessBuilder(cmd).redirectOutput(ProcessBuilder.Redirect.DISCARD)
					.redirectError(ProcessBuilder.Redirect.DISCARD).start();
			return true;
		} catch ( IOException | RuntimeException e ) {
			log.info("Unable to open a web browser with {}: {}", cmd, e.toString());
			return false;
		}
	}

	/**
	 * Get the command to open a URL in the user's preferred web browser.
	 *
	 * @param osName
	 *        the operating system name, from the {@code os.name} system
	 *        property
	 * @param env
	 *        the environment variables
	 * @param url
	 *        the URL to open
	 * @return the command, or {@code null} if there is no graphical display to
	 *         open a browser on
	 */
	public static @Nullable List<String> command(String osName, Map<String, String> env,
			String url) {
		final String os = osName.toLowerCase(Locale.ROOT);
		if ( os.startsWith("mac") || os.startsWith("darwin") ) {
			return List.of("open", url);
		} else if ( os.startsWith("windows") ) {
			// not "cmd /c start", which would interpret characters such as & in the URL
			return List.of("rundll32", "url.dll,FileProtocolHandler", url);
		} else if ( hasValue(env.get("DISPLAY")) || hasValue(env.get("WAYLAND_DISPLAY")) ) {
			return List.of("xdg-open", url);
		}
		return null;
	}

	private static boolean hasValue(@Nullable String s) {
		return s != null && !s.isBlank();
	}

}
