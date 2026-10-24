package dev.lyrirfy.lyrics;

import java.util.List;

/** Parsed, sorted lyrics for one track. */
public final class LyricsDoc {
	private final List<LyricLine> lines;
	private final long trackDurationMs;
	private final String source;

	public LyricsDoc(List<LyricLine> lines, long trackDurationMs, String source) {
		this.lines = List.copyOf(lines);
		this.trackDurationMs = Math.max(0L, trackDurationMs);
		this.source = source;
	}

	public List<LyricLine> lines() {
		return lines;
	}

	public long trackDurationMs() {
		return trackDurationMs;
	}

	public String source() {
		return source;
	}

	public boolean isEmpty() {
		return lines.isEmpty();
	}

	/** Index of the last line that started at or before {@code positionMs}, or -1. */
	public int indexAt(long positionMs) {
		int low = 0;
		int high = lines.size() - 1;
		int found = -1;
		while (low <= high) {
			int mid = (low + high) >>> 1;
			if (lines.get(mid).startMs() <= positionMs) {
				found = mid;
				low = mid + 1;
			} else {
				high = mid - 1;
			}
		}
		return found;
	}

	public LyricLine line(int index) {
		return index >= 0 && index < lines.size() ? lines.get(index) : null;
	}

	/**
	 * When the line at {@code index} should stop being displayed: either when the next line begins,
	 * or after {@code maxHoldMs} for long instrumental breaks.
	 */
	public long endOf(int index, int maxHoldMs) {
		if (index < 0 || index >= lines.size()) {
			return 0L;
		}
		LyricLine line = lines.get(index);
		long hardStop = line.startMs() + maxHoldMs;
		long next = index + 1 < lines.size() ? lines.get(index + 1).startMs() : Long.MAX_VALUE;
		long end = Math.min(next, hardStop);
		if (trackDurationMs > 0) {
			end = Math.min(end, trackDurationMs + 1500L);
		}
		return Math.max(end, line.startMs() + 250L);
	}
}
