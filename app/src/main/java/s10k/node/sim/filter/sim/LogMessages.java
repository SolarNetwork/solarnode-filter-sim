/* ==================================================================
 * LogMessages.java - 20 Oct 2026 7:05:12 pm
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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helpers for the messages filters log, which a simulation reports.
 *
 * <p>
 * SolarNode logs an expression error as
 * {@code Error evaluating … expression `<expression>`: <error>\n\nExpression root: <root>}.
 * A long expression or root would hide the error if the message were simply cut
 * off, so each is shortened by itself, and the error is kept whole.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class LogMessages {

	/** The maximum length of a quoted part, such as an expression. */
	public static final int MAX_QUOTED_LENGTH = 200;

	/** The maximum length of an expression root. */
	public static final int MAX_ROOT_LENGTH = 300;

	/** The maximum length of a whole message. */
	public static final int MAX_LENGTH = 2000;

	/** The text that starts an expression error's root. */
	public static final String ROOT_LABEL = "Expression root:";

	/** A part of a message in backticks, such as an expression. */
	private static final Pattern QUOTED = Pattern.compile("`([^`]*)`");

	/** An expression root, to the end of the message. */
	private static final Pattern ROOT = Pattern
			.compile("(\\s*" + Pattern.quote(ROOT_LABEL) + "\\s*)(.*)$", Pattern.DOTALL);

	private static final String ELLIPSIS = "…";

	private LogMessages() {
		// not available
	}

	/**
	 * Shorten a logged message: its quoted parts, its expression root, and then
	 * the whole message, if still too long.
	 *
	 * @param message
	 *        the message
	 * @return the shortened message
	 */
	public static String shorten(String message) {
		final Matcher q = QUOTED.matcher(message);
		final StringBuilder buf = new StringBuilder(message.length());
		while ( q.find() ) {
			q.appendReplacement(buf,
					Matcher.quoteReplacement("`" + abbreviate(q.group(1), MAX_QUOTED_LENGTH) + "`"));
		}
		q.appendTail(buf);
		String result = buf.toString();
		final Matcher r = ROOT.matcher(result);
		if ( r.find() ) {
			result = result.substring(0, r.start()) + r.group(1)
					+ abbreviate(r.group(2), MAX_ROOT_LENGTH);
		}
		return abbreviate(result, MAX_LENGTH);
	}

	/**
	 * Get the part of a message that identifies a problem, to count repeats of
	 * it: the message without its expression root, which differs for each
	 * datum.
	 *
	 * @param message
	 *        the message
	 * @return the message without its expression root
	 */
	public static String problem(String message) {
		final Matcher r = ROOT.matcher(message);
		return (r.find() ? message.substring(0, r.start()) : message);
	}

	private static String abbreviate(String s, int max) {
		return (s.length() > max ? s.substring(0, max) + ELLIPSIS : s);
	}

}
