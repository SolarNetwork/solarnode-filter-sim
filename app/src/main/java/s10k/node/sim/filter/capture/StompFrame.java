/* ==================================================================
 * StompFrame.java - 21 Oct 2026 2:30:00 pm
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

package s10k.node.sim.filter.capture;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A STOMP 1.2 frame, as sent over a WebSocket text message.
 *
 * <p>
 * Header values are escaped as STOMP 1.2 requires, except in {@code CONNECT}
 * and {@code CONNECTED} frames. A body ends at its {@code NUL} character, so
 * {@code content-length} headers are not needed for the text bodies SolarNode
 * sends.
 * </p>
 *
 * @param command
 *        the command, like {@code MESSAGE}
 * @param headers
 *        the headers
 * @param body
 *        the body
 * @author matt
 * @version 1.0
 */
public record StompFrame(String command, Map<String, String> headers, String body) {

	/** The {@code CONNECT} command. */
	public static final String CONNECT = "CONNECT";

	/** The {@code CONNECTED} command. */
	public static final String CONNECTED = "CONNECTED";

	/** The {@code SUBSCRIBE} command. */
	public static final String SUBSCRIBE = "SUBSCRIBE";

	/** The {@code MESSAGE} command. */
	public static final String MESSAGE = "MESSAGE";

	/** The {@code ERROR} command. */
	public static final String ERROR = "ERROR";

	/** The {@code DISCONNECT} command. */
	public static final String DISCONNECT = "DISCONNECT";

	/** The character that ends a frame. */
	public static final char NUL = '\0';

	/**
	 * Constructor.
	 *
	 * @param command
	 *        the command
	 * @param headers
	 *        the headers, which are copied
	 * @param body
	 *        the body
	 */
	public StompFrame {
		headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
	}

	/**
	 * Create a frame without a body.
	 *
	 * @param command
	 *        the command
	 * @param headers
	 *        the headers
	 * @return the frame
	 */
	public static StompFrame of(String command, Map<String, String> headers) {
		return new StompFrame(command, headers, "");
	}

	/**
	 * Get a header value.
	 *
	 * @param name
	 *        the header name
	 * @return the value, or {@code null} if the frame does not have the header
	 */
	public @Nullable String header(String name) {
		return headers.get(name);
	}

	/**
	 * Get the frame as WebSocket message text.
	 *
	 * @return the text
	 */
	public String encode() {
		final boolean escape = escapes(command);
		StringBuilder buf = new StringBuilder(command).append('\n');
		for ( Map.Entry<String, String> e : headers.entrySet() ) {
			buf.append(escape ? escape(e.getKey()) : e.getKey()).append(':')
					.append(escape ? escape(e.getValue()) : e.getValue()).append('\n');
		}
		return buf.append('\n').append(body).append(NUL).toString();
	}

	/**
	 * Parse a frame from WebSocket message text.
	 *
	 * <p>
	 * When a header is repeated, the first value is used, as STOMP 1.2 says.
	 * </p>
	 *
	 * @param text
	 *        the text
	 * @return the frame, or {@code null} if the text is only a heart-beat (end
	 *         of line characters)
	 * @throws IllegalArgumentException
	 *         if the text is not a frame
	 */
	public static @Nullable StompFrame parse(String text) {
		int pos = 0;
		final int len = text.length();
		while ( pos < len && (text.charAt(pos) == '\n' || text.charAt(pos) == '\r') ) {
			pos++;
		}
		if ( pos >= len ) {
			return null;
		}
		int eol = text.indexOf('\n', pos);
		if ( eol < 0 ) {
			throw new IllegalArgumentException("A STOMP frame needs a command line.");
		}
		final String command = line(text, pos, eol);
		final boolean escaped = escapes(command);
		final Map<String, String> headers = new LinkedHashMap<>(8);
		pos = eol + 1;
		while ( true ) {
			eol = text.indexOf('\n', pos);
			if ( eol < 0 ) {
				throw new IllegalArgumentException(
						"A STOMP frame needs a blank line after its headers.");
			}
			String line = line(text, pos, eol);
			pos = eol + 1;
			if ( line.isEmpty() ) {
				break;
			}
			int colon = line.indexOf(':');
			if ( colon < 1 ) {
				throw new IllegalArgumentException("A STOMP header needs a name and value.");
			}
			String name = line.substring(0, colon);
			String value = line.substring(colon + 1);
			headers.putIfAbsent(escaped ? unescape(name) : name, escaped ? unescape(value) : value);
		}
		int end = text.indexOf(NUL, pos);
		final String body = text.substring(pos, end < 0 ? len : end);
		return new StompFrame(command, headers, body);
	}

	private static String line(String text, int start, int eol) {
		int end = (eol > start && text.charAt(eol - 1) == '\r' ? eol - 1 : eol);
		return text.substring(start, end);
	}

	private static boolean escapes(String command) {
		return !(CONNECT.equals(command) || CONNECTED.equals(command));
	}

	private static String escape(String s) {
		StringBuilder buf = new StringBuilder(s.length());
		for ( int i = 0, len = s.length(); i < len; i++ ) {
			char c = s.charAt(i);
			switch (c) {
				case '\\' -> buf.append("\\\\");
				case '\n' -> buf.append("\\n");
				case '\r' -> buf.append("\\r");
				case ':' -> buf.append("\\c");
				default -> buf.append(c);
			}
		}
		return buf.toString();
	}

	private static String unescape(String s) {
		if ( s.indexOf('\\') < 0 ) {
			return s;
		}
		StringBuilder buf = new StringBuilder(s.length());
		for ( int i = 0, len = s.length(); i < len; i++ ) {
			char c = s.charAt(i);
			if ( c == '\\' && i + 1 < len ) {
				char n = s.charAt(++i);
				switch (n) {
					case 'n' -> buf.append('\n');
					case 'r' -> buf.append('\r');
					case 'c' -> buf.append(':');
					default -> buf.append(n);
				}
			} else {
				buf.append(c);
			}
		}
		return buf.toString();
	}

}
