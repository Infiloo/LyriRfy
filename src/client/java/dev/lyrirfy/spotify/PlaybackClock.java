package dev.lyrirfy.spotify;

/**
 * Converts occasional Spotify progress updates into a smooth, continuously advancing position.
 * Playback is paused when Spotify says it is paused, so lyrics freeze with the song.
 */
public final class PlaybackClock {
	private volatile long basePositionMs;
	private volatile long baseNanos;
	private volatile boolean playing;
	private volatile long durationMs;

	public synchronized void sync(long positionMs, boolean isPlaying, long trackDurationMs) {
		this.basePositionMs = Math.max(0L, positionMs);
		this.baseNanos = System.nanoTime();
		this.playing = isPlaying;
		this.durationMs = Math.max(0L, trackDurationMs);
	}

	public synchronized void reset() {
		basePositionMs = 0L;
		baseNanos = System.nanoTime();
		playing = false;
		durationMs = 0L;
	}

	public long positionMs() {
		long position = basePositionMs;
		if (playing) {
			position += (System.nanoTime() - baseNanos) / 1_000_000L;
		}
		if (durationMs > 0) {
			position = Math.min(position, durationMs);
		}
		return Math.max(0L, position);
	}

	public boolean playing() {
		return playing;
	}
}
