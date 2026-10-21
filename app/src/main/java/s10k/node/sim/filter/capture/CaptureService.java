/* ==================================================================
 * CaptureService.java - 21 Oct 2026 2:30:00 pm
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

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import s10k.node.sim.filter.domain.CaptureInfo;
import s10k.node.sim.filter.domain.CaptureRequest;

/**
 * Capture the datum real SolarNodes capture, as input datum for simulations.
 *
 * <p>
 * Each capture subscribes to a node's {@code /topic/datum/captured/…} topics,
 * one for each source ID pattern, where the node's Datum Queue posts each datum
 * it receives before the filters run. A capture ends when it is ended on
 * purpose, when the node ends the connection, or when no browser has watched it
 * for {@link #IDLE_TIMEOUT}.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public class CaptureService implements AutoCloseable {

	/** The most captures that can run at once. */
	public static final int MAX_CAPTURES = 4;

	/** The topic of a node's captured datum, without the source ID. */
	public static final String TOPIC_PREFIX = "/topic/datum/captured/";

	/** The source ID pattern that matches every source. */
	public static final String ALL_SOURCES = "**";

	/** How long a capture runs without a browser watching it. */
	public static final Duration IDLE_TIMEOUT = Duration.ofSeconds(60);

	/** How often the event streams get a keep-alive comment. */
	public static final Duration KEEP_ALIVE_INTERVAL = Duration.ofSeconds(15);

	/**
	 * How many keep-alive intervals between requests that keep a node's
	 * session, which the node's own pages make every minute.
	 */
	private static final int NODE_KEEP_ALIVE_TICKS = 4;

	/**
	 * A path of a page in the node's web app, which is not part of its address.
	 */
	private static final Pattern PAGE_PATH = Pattern.compile("(?:/(?:a|pub)/.*|/[^/]*\\.do)$");

	private static final Logger log = LoggerFactory.getLogger(CaptureService.class);

	private final ConcurrentMap<String, CaptureSession> sessions = new ConcurrentHashMap<>();
	private final ScheduledExecutorService scheduler;
	private final ScheduledFuture<?> keepAliveTask;
	private int ticks;

	/**
	 * Constructor.
	 */
	public CaptureService() {
		super();
		this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
			Thread t = new Thread(r, "sim-capture");
			t.setDaemon(true);
			return t;
		});
		final long interval = KEEP_ALIVE_INTERVAL.toMillis();
		this.keepAliveTask = scheduler.scheduleWithFixedDelay(this::tick, interval, interval,
				TimeUnit.MILLISECONDS);
	}

	/**
	 * Get a node's web address from the address a user entered.
	 *
	 * <p>
	 * {@code http://} is added if there is no scheme, and the path of a page of
	 * the node's web app, like {@code /a/home}, is left out.
	 * </p>
	 *
	 * @param url
	 *        the address
	 * @return the address, ending in {@code /}
	 * @throws CaptureException
	 *         if the address is not a web address
	 */
	public static URI nodeUrl(@Nullable String url) {
		String u = (url != null ? url.strip() : "");
		if ( u.isEmpty() ) {
			throw new CaptureException("Enter the node's web address, like http://solarnode.local.");
		}
		if ( !u.contains("://") ) {
			u = "http://" + u;
		}
		final URI uri;
		try {
			uri = new URI(u);
		} catch ( URISyntaxException e ) {
			throw new CaptureException("The node's web address is not valid: " + e.getReason() + ".", e);
		}
		final String scheme = (uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "");
		if ( !(scheme.equals("http") || scheme.equals("https")) ) {
			throw new CaptureException("The node's web address must start with http:// or https://.");
		}
		final String host = uri.getHost();
		if ( host == null || host.isEmpty() ) {
			throw new CaptureException(
					"The node's web address needs a host name, like http://solarnode.local.");
		}
		String path = (uri.getPath() != null ? uri.getPath() : "");
		path = PAGE_PATH.matcher(path).replaceFirst("/");
		if ( !path.endsWith("/") ) {
			path += "/";
		}
		try {
			return new URI(scheme, null, host, uri.getPort(), path, null, null);
		} catch ( URISyntaxException e ) {
			throw new CaptureException("The node's web address is not valid: " + e.getReason() + ".", e);
		}
	}

	/**
	 * Get the topics of a node's captured datum, one for each source ID
	 * pattern.
	 *
	 * <p>
	 * The node leaves a source ID's leading {@code /} out of its topic, so it
	 * is left out of the patterns too. Repeated patterns are left out, and a
	 * {@code **} pattern, which matches every source, is the only topic.
	 * </p>
	 *
	 * @param sourceIds
	 *        a comma-delimited list of Ant-style source ID patterns, or
	 *        {@code null} for every source
	 * @return the topics, like {@code /topic/datum/captured/meter/**}
	 */
	public static List<String> topics(@Nullable String sourceIds) {
		final Set<String> patterns = new LinkedHashSet<>(4);
		for ( String s : (sourceIds != null ? sourceIds.split(",") : new String[0]) ) {
			String p = s.strip();
			while ( p.startsWith("/") ) {
				p = p.substring(1);
			}
			if ( ALL_SOURCES.equals(p) ) {
				return List.of(TOPIC_PREFIX + ALL_SOURCES);
			} else if ( !p.isEmpty() ) {
				patterns.add(p);
			}
		}
		if ( patterns.isEmpty() ) {
			return List.of(TOPIC_PREFIX + ALL_SOURCES);
		}
		return patterns.stream().map(p -> TOPIC_PREFIX + p).toList();
	}

	/**
	 * Start a capture: log in to the node, and subscribe to its captured datum.
	 *
	 * @param request
	 *        the request
	 * @return the capture
	 * @throws CaptureException
	 *         if the request is not valid, or the node cannot be reached, or
	 *         does not accept the login
	 */
	public CaptureInfo start(CaptureRequest request) {
		final URI url = nodeUrl(request.url());
		final String username = (request.username() != null ? request.username().strip() : "");
		final String password = (request.password() != null ? request.password() : "");
		if ( username.isEmpty() || password.isEmpty() ) {
			throw new CaptureException("Enter the username and password you log in to the node with.");
		}
		if ( sessions.size() >= MAX_CAPTURES ) {
			throw new CaptureException(
					"The simulator is already capturing from %d nodes, the most it can at once. End one of them first."
							.formatted(MAX_CAPTURES));
		}
		final List<String> topics = topics(request.sourceIds());
		final CaptureSession session = new CaptureSession(UUID.randomUUID().toString(), url, topics);
		final NodeConnection conn = NodeConnection.open(url, username, password, topics, session);
		session.connected(conn);
		sessions.put(session.id(), session);
		log.info("Capturing datum from {} topics {}", url, topics);
		return new CaptureInfo(session.id(), url.toString(), topics);
	}

	/**
	 * Get the event stream of a capture.
	 *
	 * @param id
	 *        the capture ID
	 * @param lastEventId
	 *        the number of the last datum the browser has, or {@code null}
	 * @return the event stream, or {@code null} if there is no such capture
	 * @see CaptureSession#events(String)
	 */
	public @Nullable SseEmitter events(String id, @Nullable String lastEventId) {
		final CaptureSession session = sessions.get(id);
		return (session != null ? session.events(lastEventId) : null);
	}

	/**
	 * End a capture.
	 *
	 * @param id
	 *        the capture ID
	 * @return {@code true} if there was such a capture
	 */
	public boolean end(String id) {
		final CaptureSession session = sessions.remove(id);
		if ( session == null ) {
			return false;
		}
		session.end(null, false);
		log.info("Ended the datum capture from {}", session.url());
		return true;
	}

	private void tick() {
		try {
			final boolean keepNodeSessions = (++ticks % NODE_KEEP_ALIVE_TICKS == 0);
			for ( CaptureSession session : List.copyOf(sessions.values()) ) {
				session.keepAlive(keepNodeSessions);
				if ( session.isIdle(IDLE_TIMEOUT) && sessions.remove(session.id(), session) ) {
					session.end("No browser was watching the capture.", false);
					log.info("Ended the datum capture from {}, as no browser was watching it",
							session.url());
				}
			}
		} catch ( RuntimeException e ) {
			log.warn("Datum capture keep-alive failed: {}", e.toString(), e);
		}
	}

	/**
	 * End every capture.
	 */
	@Override
	public void close() {
		keepAliveTask.cancel(false);
		scheduler.shutdownNow();
		for ( String id : List.copyOf(sessions.keySet()) ) {
			end(id);
		}
	}

}
