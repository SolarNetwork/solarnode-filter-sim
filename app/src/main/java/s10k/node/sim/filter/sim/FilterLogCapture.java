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

import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.Nullable;
import s10k.node.sim.filter.registry.CommandNotAllowedException;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Capture the warnings and errors that SolarNetwork classes log while a
 * simulation runs, such as an expression that fails to evaluate.
 *
 * <p>
 * Filters only log these problems, so without this a simulation result would
 * not show them. A Logback appender on the {@code net.solarnetwork} logger, and
 * on the logger of the simulator's own filter subclasses (whose loggers are
 * named after their classes), passes events from a thread running a simulation
 * to that simulation. A filter on the root logger's appenders, such as the
 * console, drops those events, as the simulation result reports them instead.
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

	/** The loggers the appender is added to. */
	private static final List<String> LOGGER_NAMES = List.of("net.solarnetwork",
			"s10k.node.sim.filter.registry");

	/** The appender name. */
	private static final String APPENDER_NAME = "simulation-capture";

	/** The name of the filter added to the root logger's appenders. */
	private static final String FILTER_NAME = "simulation-captured";

	private static final ThreadLocal<@Nullable Sink> SINK = new ThreadLocal<>();

	private FilterLogCapture() {
		// not available
	}

	/**
	 * Test if an event is captured: a warning or error that a SolarNetwork
	 * class logs on a thread running a simulation.
	 */
	private static boolean captured(ILoggingEvent event) {
		if ( SINK.get() == null || !event.getLevel().isGreaterOrEqual(Level.WARN) ) {
			return false;
		}
		final String name = event.getLoggerName();
		if ( name == null ) {
			return false;
		}
		for ( String logger : LOGGER_NAMES ) {
			if ( name.equals(logger) || name.startsWith(logger + ".") ) {
				return true;
			}
		}
		return false;
	}

	private static final class CaptureAppender extends AppenderBase<ILoggingEvent> {

		@Override
		protected void append(ILoggingEvent event) {
			final Sink sink = SINK.get();
			if ( sink != null && captured(event) && !commandNotAllowed(event) ) {
				final String message = event.getFormattedMessage();
				sink.logged(event.getLevel().isGreaterOrEqual(Level.ERROR),
						withCause(message != null ? message : "", event.getThrowableProxy()));
			}
		}

	}

	/**
	 * Keep captured events out of other appenders, such as the console: the
	 * simulation result reports them instead.
	 */
	private static final class CapturedFilter extends Filter<ILoggingEvent> {

		@Override
		public FilterReply decide(ILoggingEvent event) {
			return (captured(event) ? FilterReply.DENY : FilterReply.NEUTRAL);
		}

	}

	/**
	 * Test if an event is about a command that is not allowed, which the
	 * filter's step already reports, such as a chain logging that its filter
	 * failed.
	 */
	private static boolean commandNotAllowed(ILoggingEvent event) {
		for ( IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause() ) {
			if ( CommandNotAllowedException.class.getName().equals(t.getClassName()) ) {
				return true;
			}
		}
		return false;
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
	 * Add the capturing appender to the logging system, and a filter that keeps
	 * captured events out of the root logger's appenders, if the logging system
	 * is Logback and they are not there, as after the logging system is reset.
	 */
	public static synchronized void install() {
		if ( !(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx) ) {
			return;
		}
		for ( String name : LOGGER_NAMES ) {
			final Logger logger = ctx.getLogger(name);
			if ( logger.getAppender(APPENDER_NAME) == null ) {
				final CaptureAppender appender = new CaptureAppender();
				appender.setName(APPENDER_NAME);
				appender.setContext(ctx);
				appender.start();
				logger.addAppender(appender);
			}
		}
		final Logger root = ctx.getLogger(Logger.ROOT_LOGGER_NAME);
		for ( Iterator<Appender<ILoggingEvent>> itr = root.iteratorForAppenders(); itr.hasNext(); ) {
			final Appender<ILoggingEvent> appender = itr.next();
			if ( appender.getCopyOfAttachedFiltersList().stream()
					.noneMatch(f -> FILTER_NAME.equals(f.getName())) ) {
				final CapturedFilter filter = new CapturedFilter();
				filter.setName(FILTER_NAME);
				filter.setContext(ctx);
				filter.start();
				appender.addFilter(filter);
			}
		}
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
