package dev.lyrirfy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.lyrirfy.LyriRfy;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * All user facing settings. Everything that changes how an effect looks or feels is exposed
 * as a slider in the in-game graphics menu.
 */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	public static final String DEFAULT_REDIRECT_URI = "https://gravatar.me/infiloo";
	private static final String LEGACY_CALLBACK_PATH = "/callback";

	// ------------------------------------------------------------------ master
	public boolean enabled = true;

	// ------------------------------------------------------------------ features
	public boolean enable3d = true;
	public boolean enableHud = true;
	public boolean enable2dLyrics = true;
	public boolean showWhenPaused = true;

	// ------------------------------------------------------------------ 3D world lyrics
	public double lyrics3dDistance = 7.0;
	public double lyrics3dHeight = -0.75;
	public double lyrics3dLateral = 0.0;
	public double lyrics3dScale = 1.0;
	public double lyrics3dOpacity = 1.0;
	public boolean lyrics3dFixed = false;
	public boolean lyrics3dFixedFacePlayer = true;
	public boolean lyrics3dAvoidBlocks = true;
	public double lyrics3dBobAmount = 0.06;
	public double lyrics3dBobSpeed = 0.9;
	public int lyrics3dFadeInMs = 350;
	public int lyrics3dFadeOutMs = 500;
	public int lyrics3dHoldMs = 6000;
	public int lyrics3dMaxWidth = 220;
	public int lyrics3dBackgroundAlpha = 64;
	public boolean lyrics3dShowNext = false;

	// ------------------------------------------------------------------ HUD
	public double hudX = 0.015;
	public double hudY = 0.045;
	public double hudScale = 1.0;
	public double hudOpacity = 0.85;
	public boolean hudShowArtwork = true;
	public boolean hudShowProgress = true;
	public boolean hudShowTime = true;
	public double hudArtSize = 40.0;
	public int hudBackgroundAlpha = 145;

	// ------------------------------------------------------------------ 2D lyrics
	public double lyrics2dX = 0.5;
	public double lyrics2dY = 0.74;
	public double lyrics2dScale = 1.0;
	public double lyrics2dOpacity = 1.0;
	public double lyrics2dMaxWidth = 0.62;
	public boolean lyrics2dBackground = true;
	public int lyrics2dBackgroundAlpha = 96;
	public boolean lyrics2dShowNext = false;
	public int lyrics2dFadeInMs = 300;
	public int lyrics2dFadeOutMs = 450;
	public int lyrics2dHoldMs = 6000;

	// ------------------------------------------------------------------ screen shake
	public boolean shakeEnabled = true;
	public double shakeStrength = 1.0;
	public double shakeJumpSensitivity = 1.0;
	public double shakeCameraSensitivity = 1.0;
	public double shakeDecay = 4.5;
	public double shakeFrequency = 17.0;
	public double shakeMaxOffset = 9.0;
	public boolean shakeAffectsHud = true;
	public boolean shakeAffects2d = true;

	// ------------------------------------------------------------------ spotify
	public String clientId = "";
	public String clientSecret = "";
	/**
	 * The exact redirect URI configured in the Spotify developer dashboard.
	 * Spotify requires HTTPS for everything except plain HTTP on a loopback IP,
	 * so the default points at a static HTTPS page. When the URI is not a local
	 * HTTP loopback, the login is completed by pasting the full redirect URL in
	 * the Spotify tab of the config screen.
	 */
	public String redirectUri = DEFAULT_REDIRECT_URI;
	/** Legacy field, only used to migrate old configs that stored just a port. */
	public int redirectPort = 8888;
	public String scopes = "user-read-currently-playing user-read-playback-state";
	public int pollIntervalMs = 2000;
	public boolean fetchLyrics = true;
	public boolean preferSyncedLyrics = true;

	// ------------------------------------------------------------------ ui state
	public int lastTab = 0;

	// ================================================================== persistence

	public static Config load(Path path) {
		Config config = new Config();
		if (Files.exists(path)) {
			try {
				String json = Files.readString(path, StandardCharsets.UTF_8);
				Config loaded = GSON.fromJson(json, Config.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonSyntaxException e) {
				LyriRfy.LOGGER.warn("Could not read config, using defaults", e);
			}
		}
		config.sanitize();
		return config;
	}

	public void save(Path path) {
		sanitize();
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			LyriRfy.LOGGER.warn("Could not save config", e);
		}
	}

	/** Keeps the config sane even if the json was hand edited. */
	public void sanitize() {
		lyrics3dDistance = clamp(lyrics3dDistance, 2.0, 24.0);
		lyrics3dHeight = clamp(lyrics3dHeight, -6.0, 6.0);
		lyrics3dLateral = clamp(lyrics3dLateral, -8.0, 8.0);
		lyrics3dScale = clamp(lyrics3dScale, 0.3, 4.0);
		lyrics3dOpacity = clamp(lyrics3dOpacity, 0.0, 1.0);
		lyrics3dBobAmount = clamp(lyrics3dBobAmount, 0.0, 0.6);
		lyrics3dBobSpeed = clamp(lyrics3dBobSpeed, 0.0, 4.0);
		lyrics3dFadeInMs = clamp(lyrics3dFadeInMs, 0, 3000);
		lyrics3dFadeOutMs = clamp(lyrics3dFadeOutMs, 0, 3000);
		lyrics3dHoldMs = clamp(lyrics3dHoldMs, 500, 20000);
		lyrics3dMaxWidth = clamp(lyrics3dMaxWidth, 60, 600);
		lyrics3dBackgroundAlpha = clamp(lyrics3dBackgroundAlpha, 0, 255);

		hudX = clamp(hudX, 0.0, 1.0);
		hudY = clamp(hudY, 0.0, 1.0);
		hudScale = clamp(hudScale, 0.4, 3.0);
		hudOpacity = clamp(hudOpacity, 0.05, 1.0);
		hudArtSize = clamp(hudArtSize, 0.0, 128.0);
		hudBackgroundAlpha = clamp(hudBackgroundAlpha, 0, 255);

		lyrics2dX = clamp(lyrics2dX, 0.0, 1.0);
		lyrics2dY = clamp(lyrics2dY, 0.0, 1.0);
		lyrics2dScale = clamp(lyrics2dScale, 0.3, 4.0);
		lyrics2dOpacity = clamp(lyrics2dOpacity, 0.0, 1.0);
		lyrics2dMaxWidth = clamp(lyrics2dMaxWidth, 0.15, 1.0);
		lyrics2dBackgroundAlpha = clamp(lyrics2dBackgroundAlpha, 0, 255);
		lyrics2dFadeInMs = clamp(lyrics2dFadeInMs, 0, 3000);
		lyrics2dFadeOutMs = clamp(lyrics2dFadeOutMs, 0, 3000);
		lyrics2dHoldMs = clamp(lyrics2dHoldMs, 500, 20000);

		shakeStrength = clamp(shakeStrength, 0.0, 4.0);
		shakeJumpSensitivity = clamp(shakeJumpSensitivity, 0.0, 4.0);
		shakeCameraSensitivity = clamp(shakeCameraSensitivity, 0.0, 4.0);
		shakeDecay = clamp(shakeDecay, 0.2, 15.0);
		shakeFrequency = clamp(shakeFrequency, 1.0, 45.0);
		shakeMaxOffset = clamp(shakeMaxOffset, 0.0, 40.0);

		redirectPort = clamp(redirectPort, 1024, 65535);
		redirectUri = sanitizeRedirectUri(redirectUri);
		pollIntervalMs = clamp(pollIntervalMs, 1000, 15000);
		if (scopes == null || scopes.isBlank()) {
			scopes = "user-read-currently-playing user-read-playback-state";
		}
		if (clientId == null) clientId = "";
		if (clientSecret == null) clientSecret = "";
		lastTab = clamp(lastTab, 0, 5);
	}

	/** Copies every setting from another config, keeping this object identity intact. */
	public void copyFrom(Config other) {
		enabled = other.enabled;
		enable3d = other.enable3d;
		enableHud = other.enableHud;
		enable2dLyrics = other.enable2dLyrics;
		showWhenPaused = other.showWhenPaused;

		lyrics3dDistance = other.lyrics3dDistance;
		lyrics3dHeight = other.lyrics3dHeight;
		lyrics3dLateral = other.lyrics3dLateral;
		lyrics3dScale = other.lyrics3dScale;
		lyrics3dOpacity = other.lyrics3dOpacity;
		lyrics3dFixed = other.lyrics3dFixed;
		lyrics3dFixedFacePlayer = other.lyrics3dFixedFacePlayer;
		lyrics3dAvoidBlocks = other.lyrics3dAvoidBlocks;
		lyrics3dBobAmount = other.lyrics3dBobAmount;
		lyrics3dBobSpeed = other.lyrics3dBobSpeed;
		lyrics3dFadeInMs = other.lyrics3dFadeInMs;
		lyrics3dFadeOutMs = other.lyrics3dFadeOutMs;
		lyrics3dHoldMs = other.lyrics3dHoldMs;
		lyrics3dMaxWidth = other.lyrics3dMaxWidth;
		lyrics3dBackgroundAlpha = other.lyrics3dBackgroundAlpha;
		lyrics3dShowNext = other.lyrics3dShowNext;

		hudX = other.hudX;
		hudY = other.hudY;
		hudScale = other.hudScale;
		hudOpacity = other.hudOpacity;
		hudShowArtwork = other.hudShowArtwork;
		hudShowProgress = other.hudShowProgress;
		hudShowTime = other.hudShowTime;
		hudArtSize = other.hudArtSize;
		hudBackgroundAlpha = other.hudBackgroundAlpha;

		lyrics2dX = other.lyrics2dX;
		lyrics2dY = other.lyrics2dY;
		lyrics2dScale = other.lyrics2dScale;
		lyrics2dOpacity = other.lyrics2dOpacity;
		lyrics2dMaxWidth = other.lyrics2dMaxWidth;
		lyrics2dBackground = other.lyrics2dBackground;
		lyrics2dBackgroundAlpha = other.lyrics2dBackgroundAlpha;
		lyrics2dShowNext = other.lyrics2dShowNext;
		lyrics2dFadeInMs = other.lyrics2dFadeInMs;
		lyrics2dFadeOutMs = other.lyrics2dFadeOutMs;
		lyrics2dHoldMs = other.lyrics2dHoldMs;

		shakeEnabled = other.shakeEnabled;
		shakeStrength = other.shakeStrength;
		shakeJumpSensitivity = other.shakeJumpSensitivity;
		shakeCameraSensitivity = other.shakeCameraSensitivity;
		shakeDecay = other.shakeDecay;
		shakeFrequency = other.shakeFrequency;
		shakeMaxOffset = other.shakeMaxOffset;
		shakeAffectsHud = other.shakeAffectsHud;
		shakeAffects2d = other.shakeAffects2d;

		clientId = other.clientId;
		clientSecret = other.clientSecret;
		redirectUri = other.redirectUri;
		redirectPort = other.redirectPort;
		scopes = other.scopes;
		pollIntervalMs = other.pollIntervalMs;
		fetchLyrics = other.fetchLyrics;
		preferSyncedLyrics = other.preferSyncedLyrics;

		lastTab = other.lastTab;
		sanitize();
	}

	public static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public static double clamp(double value, double min, double max) {
		if (Double.isNaN(value)) {
			return min;
		}
		return Mth.clamp(value, min, max);
	}

	public String redirectUri() {
		return redirectUri;
	}

	/**
	 * Rejects garbage and old port-only configs. Anything without a scheme is
	 * assumed to be an HTTPS URL, because Spotify no longer accepts plain HTTP
	 * for public redirect targets.
	 */
	private String sanitizeRedirectUri(String value) {
		String candidate = value == null ? "" : value.trim();
		if (candidate.isEmpty()) {
			return DEFAULT_REDIRECT_URI;
		}
		if (!candidate.contains("://")) {
			candidate = "https://" + candidate;
		}
		try {
			URI uri = URI.create(candidate);
			String scheme = uri.getScheme();
			if (scheme == null || uri.getHost() == null) {
				return DEFAULT_REDIRECT_URI;
			}
			if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
				return DEFAULT_REDIRECT_URI;
			}
			// Fragments are never part of a redirect URI.
			String normalized = candidate.split("#", 2)[0];
			URI parsed = URI.create(normalized);
			if (parsed.getPort() > 0 && isLoopbackHost(parsed.getHost())) {
				redirectPort = clamp(parsed.getPort(), 1024, 65535);
			}
			return normalized;
		} catch (IllegalArgumentException e) {
			return DEFAULT_REDIRECT_URI;
		}
	}

	/** True when the mod can host the callback itself (plain HTTP on loopback). */
	public boolean isLoopbackRedirect() {
		try {
			URI uri = URI.create(redirectUri());
			return uri.getHost() != null
					&& "http".equalsIgnoreCase(uri.getScheme())
					&& isLoopbackHost(uri.getHost());
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	/** Port of the local callback listener, or -1 when it is not a loopback URI. */
	public int loopbackPort() {
		if (!isLoopbackRedirect()) {
			return -1;
		}
		int port = URI.create(redirectUri()).getPort();
		return port > 0 ? port : 80;
	}

	/** Path the local callback listener should answer on. */
	public String redirectPath() {
		try {
			String path = URI.create(redirectUri()).getPath();
			return path == null || path.isBlank() ? "/" : path;
		} catch (IllegalArgumentException e) {
			return LEGACY_CALLBACK_PATH;
		}
	}

	public static boolean isLoopbackHost(String host) {
		if (host == null) {
			return false;
		}
		String normalized = host.trim().toLowerCase(java.util.Locale.ROOT);
		if (normalized.startsWith("[") && normalized.endsWith("]")) {
			normalized = normalized.substring(1, normalized.length() - 1);
		}
		return normalized.equals("127.0.0.1")
				|| normalized.equals("localhost")
				|| normalized.equals("::1")
				|| normalized.equals("0:0:0:0:0:0:0:1");
	}
}
