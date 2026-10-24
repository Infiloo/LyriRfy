package dev.lyrirfy.spotify;

/** Immutable snapshot of the Spotify player, safe to share between threads. */
public record NowPlaying(
		String trackId,
		String title,
		String artist,
		String album,
		long durationMs,
		long progressMs,
		String artworkUrl,
		boolean isPlaying,
		boolean isTrack
) {
	public String trackKey() {
		return (trackId == null ? "" : trackId) + "|" + title + "|" + artist + "|" + durationMs;
	}

	public double progressFraction() {
		if (durationMs <= 0) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, progressMs / (double) durationMs));
	}
}
