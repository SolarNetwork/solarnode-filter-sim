/* ==================================================================
 * FilterLogCapture.java - 19 Oct 2026 2:20:15 pm
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

package s10k.node.sim.filter.sim;

import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;

/**
 * Capture the warnings and errors that SolarNetwork classes log while a
 * simulation runs, such as an expression that fails to evaluate.
 *
 * <p>
 * Filters only log these problems, so without this a simulation result would
 * not show them. A Logback appender on the {@code net.solarnetwork} logger
 * passes events from a thread running a simulation to that simulation.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class FilterLogCapture {

	/** A receiver of logged events. */
	@FunctionalInterface
	public interface Sink {

		/**
		 * Receive a logged event.
		 *
		 * @param error
		 *        {@code true} for an error, {@code false} for a warning
		 * @param message
		 *        the message
		 */
		void logged(boolean error, String message);

	}

	/** The logger the appender is added to. */
	private static final String LOGGER_NAME = "net.solarnetwork";

	/** The appender name. */
	private static final String APPENDER_NAME = "simulation-capture";

	private static final ThreadLocal<@Nullable Sink> SINK = new ThreadLocal<>();

	private FilterLogCapture() {
		// not available
	}

	private static final class CaptureAppender extends AppenderBase<ILoggingEvent> {

		@Override
		protected void append(ILoggingEvent event) {
			final Sink sink = SINK.get();
			if ( sink != null && event.getLevel().isGreaterOrEqual(Level.WARN) ) {
				final String message = event.getFormattedMessage();
				sink.logged(event.getLevel().isGreaterOrEqual(Level.ERROR),
						withCause(message != null ? message : "", event.getThrowableProxy()));
			}
		}

	}

	/**
	 * Add a logged exception's class and message to a message, as the
	 * simulation result has no stack trace.
	 */
	private static String withCause(String message, @Nullable IThrowableProxy cause) {
		if ( cause == null ) {
			return message;
		}
		final String causeMessage = cause.getMessage();
		return message + " (" + cause.getClassName()
				+ (causeMessage != null && !causeMessage.isEmpty() ? ": " + causeMessage : "") + ")";
	}

	/**
	 * Add the capturing appender to the logging system, if it is Logback and
	 * the appender is not there, as after the logging system is reset.
	 */
	public static synchronized void install() {
		if ( !(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) ) {
			return;
		}
		final Logger logger = ctx.getLogger(LOGGER_NAME);
		if ( logger.getAppender(APPENDER_NAME) != null ) {
			return;
		}
		final CaptureAppender appender = new CaptureAppender();
		appender.setName(APPENDER_NAME);
		appender.setContext(ctx);
		appender.start();
		logger.addAppender(appender);
	}

	/**
	 * Capture events logged by the current thread.
	 *
	 * @param sink
	 *        the receiver of events, or {@code null} to stop capturing
	 */
	public static void capture(@Nullable Sink sink) {
		if ( sink != null ) {
			SINK.set(sink);
		} else {
			SINK.remove();
		}
	}

}
