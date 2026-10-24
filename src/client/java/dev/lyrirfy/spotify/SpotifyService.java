package dev.lyrirfy.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import dev.lyrirfy.LyriRfy;
import dev.lyrirfy.config.Config;
import dev.lyrirfy.config.Tokens;
import dev.lyrirfy.lyrics.LyricsDoc;
import dev.lyrirfy.lyrics.LyricsService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the whole Spotify integration: PKCE authorization (with a tiny loopback callback
 * server), token refresh, now-playing polling, artwork download and lyric fetching.
 */
public final class SpotifyService {
	public enum State {
		DISCONNECTED, CONNECTING, CONNECTED, ERROR
	}

	private static final String TOKEN_URL = "https://accounts.spotify.com/api/token";
	private static final String AUTHORIZE_URL = "https://accounts.spotify.com/authorize";
	private static final String NOW_PLAYING_URL = "https://api.spotify.com/v1/me/player/currently-playing?additional_types=track,episode";
	private static final String USER_AGENT = "LyriRfy/1.0.0 (Minecraft Fabric mod)";
	public static final Identifier ARTWORK_ID = Identifier.fromNamespaceAndPath("lyrirfy", "artwork/current");

	private final Config config;
	private final Path tokensPath;
	private final HttpClient http;
	private final LyricsService lyricsService;
	private final ExecutorService io;
	private final SecureRandom random = new SecureRandom();
	private final PlaybackClock clock = new PlaybackClock();
	private final Set<String> failedLyrics = new HashSet<>();

	private volatile Tokens tokens;
	private volatile NowPlaying nowPlaying;
	private volatile LyricsDoc lyrics;
	private volatile String lyricsTrackKey = "";
	private volatile String artworkUrl = "";
	private volatile State state = State.DISCONNECTED;
	private volatile String status = "Not connected";
	private volatile String lastAuthUrl = "";
	private volatile boolean pollInFlight;
	private volatile long lastPollAt;
	private volatile boolean clearArtworkRequested;
	private volatile boolean hasArtworkTexture;

	private final AtomicReference<NativeImage> pendingArtwork = new AtomicReference<>();
	private volatile ServerSocket callbackServer;
	private volatile String pendingVerifier;
	private volatile String pendingState;

	public SpotifyService(Config config, Path configDir) {
		this.config = config;
		this.tokensPath = configDir.resolve("lyrirfy-tokens.json");
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(10))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		this.lyricsService = new LyricsService(configDir.resolve("lyrirfy-cache").resolve("lyrics"));
		ThreadFactory factory = runnable -> {
			Thread thread = new Thread(runnable, "LyriRfy-IO");
			thread.setDaemon(true);
			return thread;
		};
		this.io = Executors.newFixedThreadPool(2, factory);
		this.tokens = Tokens.load(tokensPath);
		if (this.tokens.accessToken != null && !this.tokens.accessToken.isBlank()) {
			this.state = State.CONNECTED;
			this.status = "Connected";
		} else if (this.tokens.hasRefreshToken()) {
			this.state = State.CONNECTING;
			this.status = "Restoring Spotify session…";
		}
	}

	// ================================================================== public API

	public State state() {
		return state;
	}

	public boolean isConnected() {
		return tokens != null && (!tokens.accessToken.isBlank() || tokens.hasRefreshToken());
	}

	public String status() {
		return status;
	}

	public String lastAuthUrl() {
		return lastAuthUrl;
	}

	public NowPlaying nowPlaying() {
		return nowPlaying;
	}

	public LyricsDoc lyrics() {
		return lyrics;
	}

	public long positionMs() {
		NowPlaying current = nowPlaying;
		if (current == null) {
			return 0L;
		}
		return Math.min(clock.positionMs(), Math.max(0L, current.durationMs()));
	}

	public boolean hasArtwork() {
		return hasArtworkTexture;
	}

	public boolean isVisible(Config cfg) {
		NowPlaying current = nowPlaying;
		if (current == null) {
			return false;
		}
		return cfg.showWhenPaused || current.isPlaying();
	}

	/** Called every client tick (render thread). */
	public void tick() {
		if (!config.enabled) {
			return;
		}
		applyPendingArtwork();
		if (clearArtworkRequested) {
			clearArtworkRequested = false;
			releaseArtwork();
		}
		if (!isConnected()) {
			return;
		}
		long now = System.currentTimeMillis();
		long interval = Math.max(1000, Math.min(15000, config.pollIntervalMs));
		if (!pollInFlight && now - lastPollAt >= interval) {
			lastPollAt = now;
			pollInFlight = true;
			io.submit(this::pollNow);
		}
	}

	public void connect() {
		Config cfg = config;
		cfg.sanitize();
		if (cfg.clientId == null || cfg.clientId.isBlank()) {
			state = State.ERROR;
			status = "Enter your Spotify Client ID first.";
			return;
		}
		try {
			stopCallbackServer();
			pendingVerifier = randomUrlSafe(64);
			pendingState = randomUrlSafe(16);
			String redirectUri = cfg.redirectUri();
			String challenge = base64Url(sha256(pendingVerifier));
			String url = AUTHORIZE_URL
					+ "?client_id=" + enc(cfg.clientId)
					+ "&response_type=code"
					+ "&redirect_uri=" + enc(redirectUri)
					+ "&scope=" + enc(cfg.scopes)
					+ "&state=" + enc(pendingState)
					+ "&code_challenge_method=S256"
					+ "&code_challenge=" + enc(challenge)
					+ "&show_dialog=true";
			lastAuthUrl = url;
			LyriRfy.LOGGER.info("Spotify login URL: {}", url);

			boolean loopback = cfg.isLoopbackRedirect();
			if (loopback) {
				startCallbackServer(cfg.loopbackPort(), cfg.redirectPath());
				status = "Waiting for you to approve LyriRfy in your browser…";
			} else {
				// Spotify only redirects to HTTPS for public hosts, so we cannot
				// listen there. The user copies the redirected URL back into the GUI.
				status = "Approve LyriRfy in your browser, then paste the full URL it redirects to below.";
			}
			state = State.CONNECTING;
			try {
				Util.getPlatform().openUri(url);
			} catch (Throwable t) {
				status = "Could not open a browser. Press \"Show login URL\" and open it manually.";
			}
		} catch (Exception e) {
			state = State.ERROR;
			status = "Could not start login: " + shortMessage(e);
			stopCallbackServer();
		}
	}

	/** True while a non-loopback login is waiting for the pasted redirect URL. */
	public boolean awaitingManualCode() {
		return state == State.CONNECTING && !config.isLoopbackRedirect();
	}

	/**
	 * Completes a login from a pasted redirect URL (or a bare authorization code).
	 * Returns an empty string on success, otherwise a message for the GUI.
	 */
	public String submitRedirectInput(String input) {
		String raw = input == null ? "" : input.trim();
		if (raw.isEmpty()) {
			return "Paste the full URL Spotify redirected you to.";
		}
		String code;
		String returnedState = null;
		String error = null;
		String errorDescription = null;

		if (!raw.contains("://") && !raw.contains("?") && !raw.contains("=")) {
			// Bare authorization code.
			code = raw;
		} else {
			String query = raw;
			try {
				if (raw.contains("://")) {
					URI uri = URI.create(raw);
					query = uri.getRawQuery();
					if (query == null) {
						query = "";
					}
				} else if (raw.startsWith("?")) {
					query = raw.substring(1);
				}
				Map<String, String> values = parseQuery("?" + query);
				code = values.get("code");
				returnedState = values.get("state");
				error = values.get("error");
				errorDescription = values.get("error_description");
			} catch (IllegalArgumentException e) {
				return "That does not look like a valid redirect URL.";
			}
		}

		if (error != null && !error.isBlank()) {
			state = State.ERROR;
			status = "Spotify returned: " + error + (errorDescription == null ? "" : " (" + errorDescription + ")");
			return status;
		}
		if (code == null || code.isBlank()) {
			return "No authorization code found in that URL.";
		}
		if (returnedState != null && pendingState != null && !pendingState.equals(returnedState)) {
			state = State.ERROR;
			status = "Login failed: state mismatch. Please press Connect again.";
			return status;
		}
		if (pendingVerifier == null) {
			return "This login session expired. Press Connect with Spotify to start a new one.";
		}
		state = State.CONNECTING;
		status = "Exchanging authorization code…";
		exchangeCode(code);
		return "";
	}

	public void disconnect() {
		stopCallbackServer();
		Tokens current = tokens;
		if (current != null) {
			current.clear();
			current.save(tokensPath);
		}
		tokens = current;
		nowPlaying = null;
		lyrics = null;
		lyricsTrackKey = "";
		artworkUrl = "";
		clock.reset();
		pendingArtwork.set(null);
		clearArtworkRequested = true;
		state = State.DISCONNECTED;
		status = "Not connected";
	}

	public void refreshNow() {
		if (!isConnected()) {
			status = "Not connected";
			return;
		}
		if (!pollInFlight) {
			pollInFlight = true;
			lastPollAt = System.currentTimeMillis();
			io.submit(this::pollNow);
		}
	}

	// ================================================================== polling

	private void pollNow() {
		try {
			Tokens current = tokens;
			if (current == null || (!current.hasRefreshToken() && current.accessToken.isBlank())) {
				state = State.DISCONNECTED;
				return;
			}
			if (current.isExpired()) {
				current = refreshTokens();
				if (current == null) {
					return;
				}
			}

			long started = System.nanoTime();
			HttpResponse<String> response = requestNowPlaying(current.accessToken);
			long roundTripMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);

			if (response.statusCode() == 401) {
				current = refreshTokens();
				if (current == null) {
					return;
				}
				started = System.nanoTime();
				response = requestNowPlaying(current.accessToken);
				roundTripMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
			}

			int code = response.statusCode();
			if (code == 204) {
				clearPlayback("Nothing playing");
				return;
			}
			if (code == 429) {
				state = State.CONNECTED;
				status = "Spotify rate limit reached, slowing down…";
				lastPollAt = System.currentTimeMillis() + 5000L;
				return;
			}
			if (code != 200) {
				state = State.ERROR;
				status = "Spotify error " + code + (code == 403 ? " (check scopes / app allowlist)" : "");
				return;
			}

			NowPlaying parsed = parseNowPlaying(response.body(), roundTripMs);
			if (parsed == null) {
				clearPlayback("Nothing playing");
				return;
			}
			nowPlaying = parsed;
			clock.sync(parsed.progressMs(), parsed.isPlaying(), parsed.durationMs());
			state = State.CONNECTED;
			status = parsed.isPlaying() ? "Playing" : "Paused";
			ensureLyrics(parsed);
			ensureArtwork(parsed);
		} catch (Exception e) {
			state = State.ERROR;
			status = "Spotify request failed: " + shortMessage(e);
		} finally {
			pollInFlight = false;
		}
	}

	private void clearPlayback(String message) {
		nowPlaying = null;
		lyrics = null;
		lyricsTrackKey = "";
		artworkUrl = "";
		clock.reset();
		clearArtworkRequested = true;
		state = State.CONNECTED;
		status = message;
	}

	private HttpResponse<String> requestNowPlaying(String accessToken) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(NOW_PLAYING_URL))
				.timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + accessToken)
				.header("Accept", "application/json")
				.header("User-Agent", USER_AGENT)
				.GET()
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private NowPlaying parseNowPlaying(String body, long roundTripMs) {
		if (body == null || body.isBlank()) {
			return null;
		}
		JsonElement rootElement = JsonParser.parseString(body);
		if (!rootElement.isJsonObject()) {
			return null;
		}
		JsonObject root = rootElement.getAsJsonObject();
		if (!root.has("item") || root.get("item").isJsonNull() || !root.get("item").isJsonObject()) {
			return null;
		}
		JsonObject item = root.getAsJsonObject("item");
		boolean isPlaying = root.has("is_playing") && !root.get("is_playing").isJsonNull() && root.get("is_playing").getAsBoolean();
		long progress = root.has("progress_ms") && !root.get("progress_ms").isJsonNull() ? root.get("progress_ms").getAsLong() : 0L;
		progress += roundTripMs / 2L;

		String type = item.has("type") && !item.get("type").isJsonNull() ? item.get("type").getAsString() : "track";
		boolean isTrack = "track".equals(type);
		String title = string(item, "name", "Unknown");
		String id = string(item, "id", "");
		long duration = item.has("duration_ms") && !item.get("duration_ms").isJsonNull() ? item.get("duration_ms").getAsLong() : 0L;

		String artist = "Unknown artist";
		String album = "";
		String artwork = null;
		JsonArray images = null;

		if (isTrack) {
			JsonArray artists = item.has("artists") && item.get("artists").isJsonArray() ? item.getAsJsonArray("artists") : null;
			if (artists != null && !artists.isEmpty()) {
				StringBuilder names = new StringBuilder();
				for (int i = 0; i < artists.size(); i++) {
					JsonElement artistElement = artists.get(i);
					if (!artistElement.isJsonObject()) {
						continue;
					}
					if (names.length() > 0) {
						names.append(", ");
					}
					names.append(string(artistElement.getAsJsonObject(), "name", ""));
				}
				if (names.length() > 0) {
					artist = names.toString();
				}
			}
			if (item.has("album") && item.get("album").isJsonObject()) {
				JsonObject albumObject = item.getAsJsonObject("album");
				album = string(albumObject, "name", "");
				if (albumObject.has("images") && albumObject.get("images").isJsonArray()) {
					images = albumObject.getAsJsonArray("images");
				}
			}
		} else {
			if (item.has("show") && item.get("show").isJsonObject()) {
				JsonObject show = item.getAsJsonObject("show");
				artist = string(show, "publisher", string(show, "name", "Podcast"));
				album = string(show, "name", "");
				if (show.has("images") && show.get("images").isJsonArray()) {
					images = show.getAsJsonArray("images");
				}
			} else {
				artist = "Podcast";
			}
			if (images == null && item.has("images") && item.get("images").isJsonArray()) {
				images = item.getAsJsonArray("images");
			}
		}

		if (images != null) {
			artwork = pickImage(images);
		}
		return new NowPlaying(id, title, artist, album, duration, progress, artwork, isPlaying, isTrack);
	}

	private static String pickImage(JsonArray images) {
		String best = null;
		int bestDelta = Integer.MAX_VALUE;
		for (JsonElement element : images) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject image = element.getAsJsonObject();
			String url = string(image, "url", "");
			if (url.isBlank()) {
				continue;
			}
			int width = image.has("width") && !image.get("width").isJsonNull() ? image.get("width").getAsInt() : 300;
			int delta = Math.abs(width - 300);
			if (delta < bestDelta) {
				bestDelta = delta;
				best = url;
			}
		}
		return best;
	}

	private static String string(JsonObject object, String key, String fallback) {
		if (object.has(key) && !object.get(key).isJsonNull()) {
			try {
				return object.get(key).getAsString();
			} catch (Exception ignored) {
			}
		}
		return fallback;
	}

	// ================================================================== lyrics

	private void ensureLyrics(NowPlaying np) {
		if (!config.fetchLyrics || !np.isTrack()) {
			lyrics = null;
			return;
		}
		String key = np.trackKey();
		if (key.equals(lyricsTrackKey)) {
			return;
		}
		lyricsTrackKey = key;
		lyrics = null;
		io.submit(() -> {
			LyricsDoc doc;
			synchronized (failedLyrics) {
				if (failedLyrics.contains(key)) {
					doc = null;
				} else {
					doc = lyricsService.fetch(np);
					if (doc == null) {
						failedLyrics.add(key);
					}
				}
			}
			if (key.equals(lyricsTrackKey)) {
				lyrics = doc;
			}
		});
	}

	// ================================================================== artwork

	private void ensureArtwork(NowPlaying np) {
		String url = np.artworkUrl() == null ? "" : np.artworkUrl();
		if (url.equals(artworkUrl)) {
			return;
		}
		artworkUrl = url;
		if (url.isBlank()) {
			clearArtworkRequested = true;
			return;
		}
		io.submit(() -> downloadArtwork(url));
	}

	private void downloadArtwork(String url) {
		try {
			HttpRequest request = HttpRequest.newBuilder(URI.create(url))
					.timeout(Duration.ofSeconds(10))
					.header("User-Agent", USER_AGENT)
					.GET()
					.build();
			HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() != 200) {
				return;
			}
			NativeImage image = NativeImage.read(response.body());
			NativeImage previous = pendingArtwork.getAndSet(image);
			if (previous != null) {
				previous.close();
			}
		} catch (Exception e) {
			LyriRfy.LOGGER.warn("Could not download Spotify artwork: {}", e.toString());
		}
	}

	private void applyPendingArtwork() {
		NativeImage image = pendingArtwork.getAndSet(null);
		if (image == null) {
			return;
		}
		try {
			DynamicTexture texture = new DynamicTexture(() -> "lyrirfy artwork", image);
			Minecraft.getInstance().getTextureManager().register(ARTWORK_ID, texture);
			hasArtworkTexture = true;
		} catch (Throwable t) {
			LyriRfy.LOGGER.warn("Could not upload Spotify artwork", t);
			image.close();
		}
	}

	private void releaseArtwork() {
		if (!hasArtworkTexture) {
			return;
		}
		hasArtworkTexture = false;
		try {
			Minecraft.getInstance().getTextureManager().release(ARTWORK_ID);
		} catch (Throwable ignored) {
		}
	}

	// ================================================================== auth / tokens

	private void startCallbackServer(int port, String path) throws IOException {
		ServerSocket server = new ServerSocket();
		server.setReuseAddress(true);
		server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 1);
		server.setSoTimeout(180_000);
		callbackServer = server;
		Thread thread = new Thread(() -> serveCallback(server, path), "LyriRfy-OAuth");
		thread.setDaemon(true);
		thread.start();
	}

	private void stopCallbackServer() {
		ServerSocket server = callbackServer;
		callbackServer = null;
		if (server != null) {
			try {
				server.close();
			} catch (IOException ignored) {
			}
		}
	}

	private void serveCallback(ServerSocket server, String path) {
		long deadline = System.currentTimeMillis() + 180_000L;
		String expectedPath = path == null || path.isBlank() ? "/" : path;
		try {
			while (System.currentTimeMillis() < deadline && callbackServer == server) {
				try (Socket socket = server.accept()) {
					socket.setSoTimeout(10_000);
					BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
					String requestLine = reader.readLine();
					// Drain headers.
					String header;
					while ((header = reader.readLine()) != null && !header.isEmpty()) {
						// ignore
					}
					if (requestLine == null) {
						continue;
					}
					String[] parts = requestLine.split(" ");
					if (parts.length < 2) {
						respond(socket, 400, "Bad request", "LyriRfy could not understand the callback.");
						continue;
					}
					String target = parts[1];
					String requestPath = target;
					int queryStart = requestPath.indexOf('?');
					if (queryStart >= 0) {
						requestPath = requestPath.substring(0, queryStart);
					}
					if (!requestPath.equals(expectedPath) && !requestPath.equals(expectedPath + "/")) {
						respond(socket, 404, "Not found", "This is not the LyriRfy login callback.");
						continue;
					}
					Map<String, String> query = parseQuery(target);
					String error = query.get("error");
					String code = query.get("code");
					String returnedState = query.get("state");
					if (error != null) {
						respond(socket, 200, "Login cancelled", "Spotify returned: " + escape(error));
						state = State.ERROR;
						status = "Login cancelled: " + error;
						break;
					}
					if (code == null || code.isBlank()) {
						respond(socket, 400, "Missing code", "No authorization code was returned.");
						continue;
					}
					if (pendingState != null && returnedState != null && !pendingState.equals(returnedState)) {
						respond(socket, 400, "State mismatch", "The login response did not match. Please try again.");
						state = State.ERROR;
						status = "Login failed: state mismatch.";
						break;
					}
					respond(socket, 200, "LyriRfy is connected",
							"You can close this tab and go back to Minecraft. Enjoy the show!");
					exchangeCode(code);
					break;
				}
			}
		} catch (IOException e) {
			if (callbackServer == server) {
				state = State.ERROR;
				status = "Login server error: " + shortMessage(e);
			}
		} finally {
			try {
				server.close();
			} catch (IOException ignored) {
			}
			if (callbackServer == server) {
				callbackServer = null;
			}
		}
	}

	private void exchangeCode(String code) {
		io.submit(() -> {
			try {
				String verifier = pendingVerifier;
				if (verifier == null) {
					state = State.ERROR;
					status = "Login expired. Please connect again.";
					return;
				}
				Config cfg = config;
				Map<String, String> form = new HashMap<>();
				form.put("grant_type", "authorization_code");
				form.put("code", code);
				form.put("redirect_uri", cfg.redirectUri());
				form.put("code_verifier", verifier);
				if (cfg.clientSecret == null || cfg.clientSecret.isBlank()) {
					form.put("client_id", cfg.clientId);
				}
				HttpResponse<String> response = postForm(form);
				if (response.statusCode() != 200) {
					state = State.ERROR;
					status = "Token exchange failed (HTTP " + response.statusCode() + "). Check Client ID / Secret and Redirect URI.";
					return;
				}
				applyTokenResponse(response.body());
				state = State.CONNECTED;
				status = "Connected";
				pendingVerifier = null;
				refreshNow();
			} catch (Exception e) {
				state = State.ERROR;
				status = "Token exchange failed: " + shortMessage(e);
			}
		});
	}

	private Tokens refreshTokens() {
		try {
			Tokens current = tokens;
			if (current == null || !current.hasRefreshToken()) {
				state = State.ERROR;
				status = "Session expired. Please connect again.";
				return null;
			}
			Config cfg = config;
			Map<String, String> form = new HashMap<>();
			form.put("grant_type", "refresh_token");
			form.put("refresh_token", current.refreshToken);
			if (cfg.clientSecret == null || cfg.clientSecret.isBlank()) {
				form.put("client_id", cfg.clientId);
			}
			HttpResponse<String> response = postForm(form);
			if (response.statusCode() != 200) {
				state = State.ERROR;
				status = "Could not refresh Spotify session (HTTP " + response.statusCode() + "). Reconnect from the Spotify tab.";
				return null;
			}
			return applyTokenResponse(response.body());
		} catch (Exception e) {
			state = State.ERROR;
			status = "Could not refresh Spotify session: " + shortMessage(e);
			return null;
		}
	}

	private HttpResponse<String> postForm(Map<String, String> form) throws IOException, InterruptedException {
		StringBuilder body = new StringBuilder();
		for (Map.Entry<String, String> entry : form.entrySet()) {
			if (body.length() > 0) {
				body.append('&');
			}
			body.append(enc(entry.getKey())).append('=').append(enc(entry.getValue()));
		}
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(TOKEN_URL))
				.timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header("Accept", "application/json")
				.header("User-Agent", USER_AGENT)
				.POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
		Config cfg = config;
		if (cfg.clientSecret != null && !cfg.clientSecret.isBlank()) {
			String basic = Base64.getEncoder().encodeToString((cfg.clientId + ":" + cfg.clientSecret).getBytes(StandardCharsets.UTF_8));
			builder.header("Authorization", "Basic " + basic);
		}
		return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
	}

	private Tokens applyTokenResponse(String body) {
		JsonElement parsed = JsonParser.parseString(body);
		if (!parsed.isJsonObject()) {
			return null;
		}
		JsonObject object = parsed.getAsJsonObject();
		Tokens updated = tokens == null ? new Tokens() : tokens;
		if (object.has("access_token") && !object.get("access_token").isJsonNull()) {
			updated.accessToken = object.get("access_token").getAsString();
		}
		if (object.has("refresh_token") && !object.get("refresh_token").isJsonNull()) {
			updated.refreshToken = object.get("refresh_token").getAsString();
		}
		if (object.has("token_type") && !object.get("token_type").isJsonNull()) {
			updated.tokenType = object.get("token_type").getAsString();
		}
		if (object.has("scope") && !object.get("scope").isJsonNull()) {
			updated.scope = object.get("scope").getAsString();
		}
		long expiresIn = object.has("expires_in") && !object.get("expires_in").isJsonNull()
				? object.get("expires_in").getAsLong() : 3600L;
		updated.expiresAt = System.currentTimeMillis() + Math.max(60L, expiresIn) * 1000L;
		updated.save(tokensPath);
		tokens = updated;
		return updated;
	}

	// ================================================================== helpers

	private static String enc(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}

	private static String randomUrlSafe(int length) {
		byte[] bytes = new byte[length];
		new SecureRandom().nextBytes(bytes);
		String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		return value.length() > length ? value.substring(0, length) : value;
	}

	private static byte[] sha256(String input) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.US_ASCII));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private static Map<String, String> parseQuery(String target) {
		Map<String, String> values = new HashMap<>();
		int question = target.indexOf('?');
		if (question < 0) {
			return values;
		}
		String query = target.substring(question + 1);
		for (String pair : query.split("&")) {
			int equals = pair.indexOf('=');
			if (equals < 0) {
				continue;
			}
			try {
				String key = URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8);
				String value = URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
				values.put(key, value);
			} catch (IllegalArgumentException ignored) {
			}
		}
		return values;
	}

	private static void respond(Socket socket, int status, String title, String message) throws IOException {
		String body = "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + escape(title) + "</title>"
				+ "<style>body{font-family:system-ui,sans-serif;background:#101014;color:#f5f5f7;display:flex;"
				+ "align-items:center;justify-content:center;height:100vh;margin:0}"
				+ "main{max-width:460px;padding:32px;text-align:center}h1{color:#1ed760;font-size:28px}"
				+ "p{color:#b3b3b3;line-height:1.5}</style></head><body><main><h1>" + escape(title) + "</h1><p>"
				+ escape(message) + "</p></main></body></html>";
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		String header = "HTTP/1.1 " + status + " " + (status == 200 ? "OK" : "Error") + "\r\n"
				+ "Content-Type: text/html; charset=utf-8\r\n"
				+ "Content-Length: " + bytes.length + "\r\n"
				+ "Connection: close\r\n\r\n";
		OutputStream output = socket.getOutputStream();
		output.write(header.getBytes(StandardCharsets.US_ASCII));
		output.write(bytes);
		output.flush();
	}

	private static String escape(String value) {
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String shortMessage(Throwable throwable) {
		String message = throwable.getMessage();
		if (message == null || message.isBlank()) {
			return throwable.getClass().getSimpleName();
		}
		return message.length() > 90 ? message.substring(0, 90) + "…" : message;
	}

	public Optional<String> debugInfo() {
		return Optional.of("LyriRfy " + LyriRfy.MOD_VERSION + " / " + state + " / " + status);
	}
}
