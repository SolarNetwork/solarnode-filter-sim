/* ==================================================================
 * NodeConnection.java - 21 Oct 2026 2:30:00 pm
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

import static java.nio.charset.StandardCharsets.UTF_8;
import java.io.IOException;
import java.net.ConnectException;
import java.net.CookieManager;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A connection to a SolarNode's WebSocket, with a STOMP subscription to one of
 * its message topics.
 *
 * <p>
 * The node's web app only lets logged-in users subscribe to its datum topics,
 * and only accepts WebSocket connections from its own pages, so the simulator
 * connects in place of the browser, the way the node's pages do:
 * </p>
 *
 * <ol>
 * <li>{@code GET /csrf} gets a session and its CSRF token;</li>
 * <li>{@code POST /login} logs in, with the token, which gives a new
 * session;</li>
 * <li>{@code GET /csrf} gets the new session's token;</li>
 * <li>the WebSocket at {@code /ws}, with the session cookie, takes a STOMP
 * {@code CONNECT} frame with the token as a header, then a
 * {@code SUBSCRIBE}.</li>
 * </ol>
 *
 * <p>
 * Like the node's pages, {@link #keepAlive()} requests {@code /csrf} now and
 * then, so the session does not expire.
 * </p>
 *
 * @author matt
 * @version 1.0
 */
public final class NodeConnection implements WebSocket.Listener, AutoCloseable {

	/** How long to wait for the node to answer. */
	public static final Duration TIMEOUT = Duration.ofSeconds(15);

	/** The STOMP subscription ID. */
	private static final String SUBSCRIPTION_ID = "0";

	private static final Logger log = LoggerFactory.getLogger(NodeConnection.class);

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	/** What a connection reports. */
	public interface Handler {

		/**
		 * Handle a message from the subscription.
		 *
		 * @param frame
		 *        the {@code MESSAGE} frame
		 */
		void message(StompFrame frame);

		/**
		 * Handle the end of the connection, unless it was closed with
		 * {@link NodeConnection#close()}.
		 *
		 * @param problem
		 *        why it ended
		 */
		void ended(String problem);

	}

	/** A node session's CSRF token. */
	private record CsrfToken(String headerName, String parameterName, String token) {

	}

	private final URI base;
	private final HttpClient client;
	private final Handler handler;
	private final StringBuilder text = new StringBuilder();
	private final CompletableFuture<StompFrame> connected = new CompletableFuture<>();
	private final AtomicBoolean ended = new AtomicBoolean();
	private volatile @Nullable WebSocket webSocket;

	private NodeConnection(URI base, Handler handler) {
		super();
		this.base = base;
		this.handler = handler;
		this.client = HttpClient.newBuilder().cookieHandler(new CookieManager()).connectTimeout(TIMEOUT)
				.followRedirects(HttpClient.Redirect.NORMAL).build();
	}

	/**
	 * Log in to a node and subscribe to one of its message topics.
	 *
	 * @param base
	 *        the node's web address, ending in {@code /}
	 * @param username
	 *        the username to log in with
	 * @param password
	 *        the password to log in with
	 * @param destination
	 *        the topic to subscribe to, like {@code /topic/datum/captured/**}
	 * @param handler
	 *        the handler of the topic's messages
	 * @return the connection
	 * @throws CaptureException
	 *         if the node cannot be reached, or does not accept the login or
	 *         subscription
	 */
	public static NodeConnection open(URI base, String username, String password, String destination,
			Handler handler) {
		NodeConnection conn = new NodeConnection(base, handler);
		try {
			conn.login(username, password);
			conn.subscribe(conn.csrf(), destination);
			return conn;
		} catch ( RuntimeException e ) {
			conn.close();
			throw e;
		}
	}

	/**
	 * Request the node's {@code /csrf} page, so its session does not expire.
	 */
	public void keepAlive() {
		client.sendAsync(get("csrf"), HttpResponse.BodyHandlers.discarding()).exceptionally(e -> {
			log.debug("Keep-alive request to {} failed: {}", base, e.toString());
			return null;
		});
	}

	/**
	 * Close the connection, without telling the handler.
	 */
	@Override
	public void close() {
		if ( !ended.compareAndSet(false, true) ) {
			return;
		}
		final WebSocket ws = webSocket;
		if ( ws != null ) {
			try {
				ws.sendText(StompFrame.of(StompFrame.DISCONNECT, Map.of()).encode(), true)
						.thenCompose(w -> w.sendClose(WebSocket.NORMAL_CLOSURE, ""))
						.get(2, TimeUnit.SECONDS);
			} catch ( ExecutionException | TimeoutException e ) {
				// closing anyway
			} catch ( InterruptedException e ) {
				Thread.currentThread().interrupt();
			} finally {
				ws.abort();
			}
		}
		client.shutdownNow();
	}

	/* === Logging in === */

	private HttpRequest get(String path) {
		return HttpRequest.newBuilder(base.resolve(path)).timeout(TIMEOUT)
				.header("Accept", "application/json").GET().build();
	}

	private <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> body) {
		try {
			return client.send(req, body);
		} catch ( IOException e ) {
			throw new CaptureException(connectProblem(e), e);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new CaptureException("The connection to the node was interrupted.", e);
		}
	}

	private String connectProblem(Throwable e) {
		if ( causedBy(e, UnknownHostException.class, UnresolvedAddressException.class) ) {
			return "The node's address " + base.getHost() + " was not found.";
		}
		if ( causedBy(e, HttpTimeoutException.class) ) {
			return "The node at " + base + " did not answer in time.";
		}
		if ( causedBy(e, ConnectException.class) ) {
			return "Unable to connect to the node at " + base + ".";
		}
		String msg = e.getMessage();
		return "Unable to connect to the node at " + base + ": "
				+ (msg != null ? msg : e.getClass().getSimpleName());
	}

	private static boolean causedBy(Throwable e, Class<?>... types) {
		// a cause chain can loop back on itself, so only follow a few causes
		Throwable t = e;
		for ( int depth = 0; t != null && depth < 8; t = t.getCause(), depth++ ) {
			for ( Class<?> type : types ) {
				if ( type.isInstance(t) ) {
					return true;
				}
			}
		}
		return false;
	}

	private static @Nullable String text(JsonNode node) {
		return (node.isString() ? node.asString() : null);
	}

	/**
	 * Get the session's CSRF token.
	 */
	private CsrfToken csrf() {
		HttpResponse<String> res = send(get("csrf"), HttpResponse.BodyHandlers.ofString());
		if ( res.statusCode() != 200 ) {
			throw new CaptureException(notNodeProblem("HTTP " + res.statusCode()));
		}
		try {
			JsonNode json = MAPPER.readTree(res.body());
			String headerName = text(json.path("headerName"));
			String parameterName = text(json.path("parameterName"));
			String token = text(json.path("token"));
			if ( headerName != null && parameterName != null && token != null ) {
				return new CsrfToken(headerName, parameterName, token);
			}
		} catch ( JacksonException e ) {
			// not JSON
		}
		throw new CaptureException(notNodeProblem("no CSRF token"));
	}

	private String notNodeProblem(String reason) {
		return "The web site at " + base + " does not look like a SolarNode that is set up (" + reason
				+ ").";
	}

	private void login(String username, String password) {
		final CsrfToken csrf = csrf();
		final Map<String, String> form = new LinkedHashMap<>(4);
		form.put("username", username);
		form.put("password", password);
		form.put(csrf.parameterName(), csrf.token());
		final String body = form.entrySet().stream().map(
				e -> URLEncoder.encode(e.getKey(), UTF_8) + "=" + URLEncoder.encode(e.getValue(), UTF_8))
				.collect(Collectors.joining("&"));
		final HttpRequest req = HttpRequest.newBuilder(base.resolve("login")).timeout(TIMEOUT)
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(HttpRequest.BodyPublishers.ofString(body)).build();
		// the node redirects to its home page, or back to the login page with
		// a login_error parameter
		HttpResponse<Void> res = send(req, HttpResponse.BodyHandlers.discarding());
		String query = res.uri().getQuery();
		if ( query != null && query.contains("login_error") ) {
			throw new CaptureException("The node did not accept the username and password.");
		}
		if ( res.statusCode() >= 400 ) {
			throw new CaptureException(
					"The node did not accept the login (HTTP " + res.statusCode() + ").");
		}
	}

	/* === WebSocket === */

	private URI webSocketUri() {
		URI ws = base.resolve("ws");
		try {
			return new URI("https".equalsIgnoreCase(ws.getScheme()) ? "wss" : "ws", ws.getUserInfo(),
					ws.getHost(), ws.getPort(), ws.getPath(), null, null);
		} catch ( URISyntaxException e ) {
			throw new CaptureException("The node's WebSocket address is not valid: " + e.getMessage(),
					e);
		}
	}

	private void subscribe(CsrfToken csrf, String destination) {
		final WebSocket ws;
		try {
			ws = client.newWebSocketBuilder().connectTimeout(TIMEOUT).buildAsync(webSocketUri(), this)
					.get(TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
		} catch ( ExecutionException e ) {
			Throwable cause = (e.getCause() != null ? e.getCause() : e);
			if ( cause instanceof WebSocketHandshakeException h ) {
				throw new CaptureException("The node refused the WebSocket connection (HTTP "
						+ h.getResponse().statusCode() + ").", h);
			}
			throw new CaptureException(connectProblem(cause), cause);
		} catch ( TimeoutException e ) {
			throw new CaptureException("The node's WebSocket did not answer in time.", e);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new CaptureException("The connection to the node was interrupted.", e);
		}
		webSocket = ws;

		final Map<String, String> headers = new LinkedHashMap<>(4);
		headers.put("accept-version", "1.2,1.1");
		headers.put("host", base.getHost());
		headers.put("heart-beat", "0,0");
		headers.put(csrf.headerName(), csrf.token());
		sendFrame(ws, StompFrame.of(StompFrame.CONNECT, headers));
		final StompFrame reply;
		try {
			reply = connected.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch ( ExecutionException e ) {
			// end() completes the future with the problem
			final Throwable cause = e.getCause();
			final String msg = (cause != null ? cause.getMessage() : null);
			throw new CaptureException(msg != null ? msg : "The node closed the WebSocket connection.",
					e);
		} catch ( TimeoutException e ) {
			throw new CaptureException("The node's WebSocket did not answer in time.", e);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new CaptureException("The connection to the node was interrupted.", e);
		}
		if ( !StompFrame.CONNECTED.equals(reply.command()) ) {
			throw new CaptureException("The node refused the WebSocket connection: " + errorText(reply));
		}
		sendFrame(ws, StompFrame.of(StompFrame.SUBSCRIBE,
				Map.of("id", SUBSCRIPTION_ID, "destination", destination)));
	}

	private static String errorText(StompFrame frame) {
		String msg = frame.header("message");
		if ( msg == null || msg.isBlank() ) {
			msg = frame.body().isBlank() ? frame.command() : frame.body().strip();
		}
		return msg;
	}

	private void sendFrame(WebSocket ws, StompFrame frame) {
		try {
			ws.sendText(frame.encode(), true).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
		} catch ( ExecutionException | TimeoutException e ) {
			throw new CaptureException("Unable to send to the node's WebSocket.", e);
		} catch ( InterruptedException e ) {
			Thread.currentThread().interrupt();
			throw new CaptureException("The connection to the node was interrupted.", e);
		}
	}

	private void end(String problem) {
		// while connecting, open() reports the problem
		final boolean connecting = connected.completeExceptionally(new IOException(problem));
		if ( ended.compareAndSet(false, true) ) {
			final WebSocket ws = webSocket;
			if ( ws != null ) {
				ws.abort();
			}
			client.shutdownNow();
			if ( !connecting ) {
				handler.ended(problem);
			}
		}
	}

	@Override
	public void onOpen(WebSocket webSocket) {
		webSocket.request(1);
	}

	@Override
	public @Nullable CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
		text.append(data);
		if ( last ) {
			final String msg = text.toString();
			text.setLength(0);
			handleText(msg);
		}
		webSocket.request(1);
		return null;
	}

	private void handleText(String msg) {
		final StompFrame frame;
		try {
			frame = StompFrame.parse(msg);
		} catch ( IllegalArgumentException e ) {
			log.debug("Ignoring invalid STOMP frame from {}: {}", base, e.getMessage());
			return;
		}
		if ( frame == null ) {
			// heart-beat
			return;
		}
		switch (frame.command()) {
			case StompFrame.CONNECTED -> connected.complete(frame);
			case StompFrame.MESSAGE -> {
				if ( !ended.get() ) {
					handler.message(frame);
				}
			}
			case StompFrame.ERROR -> {
				if ( !connected.complete(frame) ) {
					end("The node sent an error: " + errorText(frame));
				}
			}
			default -> {
				// ignore RECEIPT and others
			}
		}
	}

	@Override
	public @Nullable CompletionStage<?> onClose(WebSocket webSocket, int statusCode,
			@Nullable String reason) {
		end("The node closed the connection" + (reason != null && !reason.isBlank() ? ": " + reason : "")
				+ ".");
		return null;
	}

	@Override
	public void onError(WebSocket webSocket, Throwable error) {
		String msg = error.getMessage();
		end("The connection to the node failed: "
				+ (msg != null ? msg : error.getClass().getSimpleName()) + ".");
	}

}
