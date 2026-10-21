/* ==================================================================
 * CaptureSession.java - 21 Oct 2026 2:30:00 pm
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

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A datum capture from one node: the datum captured so far, and the browsers
 * watching it.
 *
 * <p>
 * Browsers watch a capture through an event stream (see
 * {@link #events(String)}) with these events:
 * </p>
 *
 * <dl>
 * <dt>{@code state}</dt>
 * <dd>{@code {"state":"Capturing"|"Ended","count":n,"guessed":bool}}, plus a
 * {@code message} and {@code error} once it ended. It comes first, when the
 * sample types start being guessed, and when the capture ends.</dd>
 * <dt>{@code datum}</dt>
 * <dd>each datum, in the SolarNode datum JSON format, with its number (from 1)
 * as the event ID.</dd>
 * </dl>
 *
 * <p>
 * The datum are kept, so a browser that reconnects with a {@code Last-Event-ID}
 * gets the datum after that number.
 * </p>
 *
 * <p>
 * The node sends a datum once for each subscription its source ID matches, so
 * with overlapping source ID patterns a datum comes more than once, each time
 * with the same message body but a different subscription. Those repeats are
 * dropped.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class CaptureSession implements NodeConnection.Handler {

	/** The most datum a capture keeps. */
	public static final int MAX_DATUM = 10_000;

	/** How long an event stream stays open before the browser reconnects. */
	public static final Duration EVENTS_TIMEOUT = Duration.ofMinutes(30);

	/** The {@code state} value of a capture that is running. */
	public static final String STATE_CAPTURING = "Capturing";

	/** The {@code state} value of a capture that ended. */
	public static final String STATE_ENDED = "Ended";

	/**
	 * How many recent message bodies are remembered, to find the repeats of a
	 * datum that several subscriptions match.
	 */
	private static final int RECENT_MESSAGES = 64;

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final String id;
	private final URI url;
	private final List<String> topics;
	private final List<String> datum = new ArrayList<>();
	/** Recent message bodies, and the subscriptions that delivered them. */
	private final Map<String, Set<String>> recent = new LinkedHashMap<>(RECENT_MESSAGES * 2);
	private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
	private boolean guessed;
	private boolean ended;
	private @Nullable String endMessage;
	private boolean endError;
	private volatile @Nullable NodeConnection connection;
	private volatile long idleSince = System.currentTimeMillis();

	/**
	 * Constructor.
	 *
	 * @param id
	 *        the capture ID
	 * @param url
	 *        the node's web address
	 * @param topics
	 *        the topics the capture subscribes to
	 */
	public CaptureSession(String id, URI url, List<String> topics) {
		super();
		this.id = id;
		this.url = url;
		this.topics = List.copyOf(topics);
	}

	/**
	 * Get the capture ID.
	 *
	 * @return the ID
	 */
	public String id() {
		return id;
	}

	/**
	 * Get the node's web address.
	 *
	 * @return the address
	 */
	public URI url() {
		return url;
	}

	/**
	 * Get the topics the capture subscribes to.
	 *
	 * @return the topics
	 */
	public List<String> topics() {
		return topics;
	}

	/**
	 * Get the number of datum captured.
	 *
	 * @return the number of datum
	 */
	public synchronized int count() {
		return datum.size();
	}

	/**
	 * Set the connection the datum come from.
	 *
	 * @param conn
	 *        the connection, which is closed when the capture ends
	 */
	public void connected(NodeConnection conn) {
		synchronized ( this ) {
			if ( !ended ) {
				connection = conn;
				return;
			}
		}
		conn.close();
	}

	@Override
	public void message(StompFrame frame) {
		synchronized ( this ) {
			if ( repeated(frame) ) {
				return;
			}
		}
		DatumMessages.MessageDatum d = DatumMessages.datum(frame.body());
		if ( d != null ) {
			add(d);
		}
	}

	@Override
	public void ended(String problem) {
		end(problem, true);
	}

	/**
	 * Test if a message repeats one another subscription delivered.
	 *
	 * <p>
	 * A message with the same body as a recent one is a repeat if a different
	 * subscription delivered the recent one. From the same subscription it is a
	 * new datum that happens to be the same.
	 * </p>
	 */
	private boolean repeated(StompFrame frame) {
		if ( topics.size() < 2 ) {
			return false;
		}
		final String body = frame.body();
		final String subscription = Objects.requireNonNullElse(frame.header("subscription"), "");
		Set<String> subscriptions = recent.get(body);
		if ( subscriptions != null && !subscriptions.contains(subscription) ) {
			subscriptions.add(subscription);
			return true;
		}
		subscriptions = new HashSet<>(4);
		subscriptions.add(subscription);
		recent.remove(body);
		recent.put(body, subscriptions);
		if ( recent.size() > RECENT_MESSAGES ) {
			Iterator<String> eldest = recent.keySet().iterator();
			eldest.next();
			eldest.remove();
		}
		return false;
	}

	private void add(DatumMessages.MessageDatum d) {
		boolean full;
		synchronized ( this ) {
			if ( ended ) {
				return;
			}
			datum.add(d.json());
			if ( d.guessed() && !guessed ) {
				guessed = true;
				broadcast(this::stateEvent);
			}
			final int n = datum.size();
			broadcast(() -> datumEvent(n));
			full = datum.size() >= MAX_DATUM;
		}
		if ( full ) {
			end("The capture stopped at %,d datum, the most it keeps.".formatted(MAX_DATUM), false);
		}
	}

	/**
	 * End the capture: close the connection, and tell the browsers.
	 *
	 * @param message
	 *        why it ended, or {@code null} if it was ended on purpose
	 * @param error
	 *        {@code true} if it ended because of a problem
	 */
	public void end(@Nullable String message, boolean error) {
		final NodeConnection conn;
		synchronized ( this ) {
			if ( ended ) {
				return;
			}
			ended = true;
			endMessage = message;
			endError = error;
			conn = connection;
			connection = null;
			broadcast(this::stateEvent);
			for ( SseEmitter emitter : emitters ) {
				emitter.complete();
			}
			emitters.clear();
		}
		if ( conn != null ) {
			conn.close();
		}
	}

	/**
	 * Get an event stream of the capture.
	 *
	 * @param lastEventId
	 *        the number of the last datum the browser has, or {@code null} to
	 *        start from the first
	 * @return the event stream
	 */
	public SseEmitter events(@Nullable String lastEventId) {
		final SseEmitter emitter = new SseEmitter(EVENTS_TIMEOUT.toMillis());
		synchronized ( this ) {
			int from = 0;
			if ( lastEventId != null ) {
				try {
					from = Math.clamp(Integer.parseInt(lastEventId.strip()), 0, datum.size());
				} catch ( NumberFormatException e ) {
					// from the first
				}
			}
			try {
				emitter.send(stateEvent());
				for ( int n = from + 1; n <= datum.size(); n++ ) {
					emitter.send(datumEvent(n));
				}
			} catch ( IOException e ) {
				emitter.completeWithError(e);
				return emitter;
			}
			if ( ended ) {
				emitter.complete();
				return emitter;
			}
			emitters.add(emitter);
		}
		final Runnable remove = () -> remove(emitter);
		emitter.onCompletion(remove);
		emitter.onTimeout(remove);
		emitter.onError(_ -> remove(emitter));
		return emitter;
	}

	private void remove(SseEmitter emitter) {
		if ( emitters.remove(emitter) && emitters.isEmpty() ) {
			idleSince = System.currentTimeMillis();
		}
	}

	/**
	 * Keep the event streams open.
	 *
	 * <p>
	 * Sends a comment to each event stream, which also finds the browsers that
	 * went away, and asks the node to keep its session.
	 * </p>
	 *
	 * @param keepNodeSession
	 *        {@code true} to ask the node to keep its session
	 */
	public void keepAlive(boolean keepNodeSession) {
		for ( SseEmitter emitter : emitters ) {
			try {
				emitter.send(SseEmitter.event().comment("keep-alive"));
			} catch ( IOException | IllegalStateException e ) {
				remove(emitter);
			}
		}
		final NodeConnection conn = connection;
		if ( keepNodeSession && conn != null ) {
			conn.keepAlive();
		}
	}

	/**
	 * Test if no browser has watched the capture for a while.
	 *
	 * @param idle
	 *        how long without a browser counts as idle
	 * @return {@code true} if no browser has watched it for {@code idle}
	 */
	public boolean isIdle(Duration idle) {
		return emitters.isEmpty() && System.currentTimeMillis() - idleSince > idle.toMillis();
	}

	// each send needs its own event builder
	private void broadcast(Supplier<SseEmitter.SseEventBuilder> event) {
		for ( SseEmitter emitter : emitters ) {
			try {
				emitter.send(event.get());
			} catch ( IOException | IllegalStateException e ) {
				remove(emitter);
			}
		}
	}

	private SseEmitter.SseEventBuilder stateEvent() {
		ObjectNode state = MAPPER.createObjectNode();
		state.put("state", ended ? STATE_ENDED : STATE_CAPTURING);
		state.put("count", datum.size());
		state.put("guessed", guessed);
		if ( ended ) {
			if ( endMessage != null ) {
				state.put("message", endMessage);
			}
			state.put("error", endError);
		}
		return SseEmitter.event().name("state").data(MAPPER.writeValueAsString(state),
				MediaType.APPLICATION_JSON);
	}

	private SseEmitter.SseEventBuilder datumEvent(int n) {
		return SseEmitter.event().name("datum").id(String.valueOf(n)).data(datum.get(n - 1),
				MediaType.APPLICATION_JSON);
	}

}
