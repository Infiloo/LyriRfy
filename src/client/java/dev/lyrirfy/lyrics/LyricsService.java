package dev.lyrirfy.lyrics;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lyrirfy.LyriRfy;
import dev.lyrirfy.spotify.NowPlaying;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Fetches time synced lyrics from LRCLIB (free, no API key, https://lrclib.net).
 * Results are cached on disk so replaying a song works offline.
 */
public final class LyricsService {
	private static final String USER_AGENT = "LyriRfy/1.0.0 (Minecraft Fabric mod; +https://lrclib.net)";
	private final HttpClient http;
	private final Path cacheDir;

	public LyricsService(Path cacheDir) {
		this.cacheDir = cacheDir;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(8))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.build();
		try {
			Files.createDirectories(cacheDir);
		} catch (IOException e) {
			LyriRfy.LOGGER.warn("Could not create lyric cache directory", e);
		}
	}

	/** Blocking; call from a background thread. Returns null when nothing was found. */
	public LyricsDoc fetch(NowPlaying nowPlaying) {
		if (nowPlaying == null || !nowPlaying.isTrack() || nowPlaying.title() == null || nowPlaying.title().isBlank()) {
			return null;
		}
		long durationSeconds = Math.max(0L, nowPlaying.durationMs() / 1000L);
		String key = cacheKey(nowPlaying, durationSeconds);
		Path cached = cacheDir.resolve(key + ".lrc");
		Path miss = cacheDir.resolve(key + ".miss");

		if (Files.exists(cached)) {
			try {
				return LrcParser.parse(Files.readString(cached, StandardCharsets.UTF_8), nowPlaying.durationMs());
			} catch (IOException e) {
				LyriRfy.LOGGER.warn("Could not read cached lyrics", e);
			}
		}
		if (Files.exists(miss)) {
			return null;
		}

		try {
			String synced = requestGet(nowPlaying, durationSeconds);
			if (synced == null) {
				synced = requestSearch(nowPlaying, durationSeconds);
			}
			if (synced == null || synced.isBlank()) {
				Files.writeString(miss, "no synced lyrics", StandardCharsets.UTF_8);
				return null;
			}
			LyricsDoc doc = LrcParser.parse(synced, nowPlaying.durationMs());
			if (!doc.isEmpty()) {
				Files.writeString(cached, synced, StandardCharsets.UTF_8);
				return doc;
			}
		} catch (IOException | InterruptedException e) {
			if (e instanceof InterruptedException) {
				Thread.currentThread().interrupt();
			}
			LyriRfy.LOGGER.warn("Lyrics lookup failed for {} - {}", nowPlaying.artist(), nowPlaying.title(), e.toString());
		}
		return null;
	}

	private String requestGet(NowPlaying np, long durationSeconds) throws IOException, InterruptedException {
		String url = "https://lrclib.net/api/get?artist_name=" + enc(np.artist())
				+ "&track_name=" + enc(np.title())
				+ "&album_name=" + enc(np.album())
				+ "&duration=" + durationSeconds;
		HttpResponse<String> response = get(url);
		if (response.statusCode() != 200) {
			return null;
		}
		JsonElement parsed = JsonParser.parseString(response.body());
		if (!parsed.isJsonObject()) {
			return null;
		}
		JsonObject object = parsed.getAsJsonObject();
		if (object.has("instrumental") && !object.get("instrumental").isJsonNull() && object.get("instrumental").getAsBoolean()) {
			return null;
		}
		if (object.has("syncedLyrics") && !object.get("syncedLyrics").isJsonNull()) {
			return object.get("syncedLyrics").getAsString();
		}
		return null;
	}

	private String requestSearch(NowPlaying np, long durationSeconds) throws IOException, InterruptedException {
		String url = "https://lrclib.net/api/search?track_name=" + enc(np.title()) + "&artist_name=" + enc(np.artist());
		HttpResponse<String> response = get(url);
		if (response.statusCode() != 200) {
			return null;
		}
		JsonElement parsed = JsonParser.parseString(response.body());
		if (!parsed.isJsonArray()) {
			return null;
		}
		JsonArray results = parsed.getAsJsonArray();
		String fallback = null;
		for (JsonElement element : results) {
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject object = element.getAsJsonObject();
			if (!object.has("syncedLyrics") || object.get("syncedLyrics").isJsonNull()) {
				continue;
			}
			String synced = object.get("syncedLyrics").getAsString();
			if (synced == null || synced.isBlank()) {
				continue;
			}
			if (fallback == null) {
				fallback = synced;
			}
			if (object.has("duration") && !object.get("duration").isJsonNull()) {
				long candidate = object.get("duration").getAsLong();
				if (Math.abs(candidate - durationSeconds) <= 4) {
					return synced;
				}
			}
		}
		return fallback;
	}

	private HttpResponse<String> get(String url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofSeconds(8))
				.header("User-Agent", USER_AGENT)
				.header("Accept", "application/json")
				.GET()
				.build();
		return http.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String cacheKey(NowPlaying np, long durationSeconds) {
		String raw = np.artist() + "|" + np.title() + "|" + np.album() + "|" + durationSeconds;
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-1");
			return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			return Integer.toHexString(raw.hashCode());
		}
	}

	private static String enc(String value) {
		return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
	}
}
