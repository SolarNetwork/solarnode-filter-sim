/* ==================================================================
 * StartupMessage.java - 19 Oct 2026 4:12:40 pm
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

package s10k.node.sim.filter.web;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Print a message with the application's URL once it has started, in place of
 * Spring Boot's banner.
 *
 * <p>
 * The URL has the port the web server is listening on, the
 * {@code server.address} (when the server listens on a specific address), and
 * the {@code server.servlet.context-path}.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
@Component
public class StartupMessage implements ApplicationListener<ApplicationReadyEvent> {

	private final @Nullable BuildProperties buildProperties;

	/**
	 * Constructor.
	 *
	 * @param buildProperties
	 *        the build properties, if available
	 */
	public StartupMessage(ObjectProvider<BuildProperties> buildProperties) {
		super();
		this.buildProperties = buildProperties.getIfAvailable();
	}

	@Override
	public void onApplicationEvent(ApplicationReadyEvent event) {
		if ( !(event.getApplicationContext() instanceof WebServerApplicationContext ctx) ) {
			return;
		}
		final WebServer server = ctx.getWebServer();
		if ( server == null ) {
			return;
		}
		System.out.println();
		System.out.println(message(url(ctx.getEnvironment(), server.getPort())));
	}

	/**
	 * Get the message.
	 *
	 * @param url
	 *        the application URL
	 * @return the message
	 */
	public String message(String url) {
		final BuildProperties props = this.buildProperties;
		final String name = (props != null && props.getName() != null ? props.getName()
				: InfoController.DEFAULT_APP_NAME);
		final String version = (props != null ? props.getVersion() : null);
		return "%s%s started, visit%n%s".formatted(name, version != null ? " (" + version + ")" : "",
				url);
	}

	/**
	 * Get the application URL.
	 *
	 * @param env
	 *        the environment
	 * @param port
	 *        the port the web server is listening on
	 * @return the URL
	 */
	public static String url(Environment env, int port) {
		String host = env.getProperty("server.address", "").trim();
		if ( host.isEmpty() || "0.0.0.0".equals(host) || "::".equals(host)
				|| "127.0.0.1".equals(host) ) {
			host = "localhost";
		} else if ( host.indexOf(':') >= 0 && !host.startsWith("[") ) {
			host = "[" + host + "]";
		}
		String contextPath = env.getProperty("server.servlet.context-path", "").trim();
		if ( "/".equals(contextPath) ) {
			contextPath = "";
		}
		return "http://%s:%d%s".formatted(host, port, contextPath);
	}

}
