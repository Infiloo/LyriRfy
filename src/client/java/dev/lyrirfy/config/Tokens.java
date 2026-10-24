package dev.lyrirfy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import dev.lyrirfy.LyriRfy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** OAuth tokens, stored separately from the normal config. */
public final class Tokens {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public String accessToken = "";
	public String refreshToken = "";
	public String tokenType = "Bearer";
	public String scope = "";
	/** Epoch millis at which the access token stops working. */
	public long expiresAt = 0L;

	public boolean isExpired() {
		return accessToken.isBlank() || System.currentTimeMillis() >= expiresAt - 30_000L;
	}

	public boolean hasRefreshToken() {
		return refreshToken != null && !refreshToken.isBlank();
	}

	public static Tokens load(Path path) {
		if (!Files.exists(path)) {
			return new Tokens();
		}
		try {
			Tokens tokens = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Tokens.class);
			return tokens == null ? new Tokens() : tokens;
		} catch (IOException | JsonSyntaxException e) {
			LyriRfy.LOGGER.warn("Could not read Spotify tokens", e);
			return new Tokens();
		}
	}

	public void save(Path path) {
		try {
			Path parent = path.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			Files.writeString(path, GSON.toJson(this), StandardCharsets.UTF_8);
			try {
				// Tokens are secrets; keep them readable by the owner only where the OS supports it.
				Files.setPosixFilePermissions(path, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
			} catch (UnsupportedOperationException | IOException ignored) {
				// Windows / exotic filesystems.
			}
		} catch (IOException e) {
			LyriRfy.LOGGER.warn("Could not save Spotify tokens", e);
		}
	}

	public void clear() {
		accessToken = "";
		refreshToken = "";
		scope = "";
		expiresAt = 0L;
	}
}
