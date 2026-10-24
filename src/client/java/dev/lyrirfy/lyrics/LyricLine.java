package dev.lyrirfy.lyrics;

/** A single timed lyric line. An empty text marks an instrumental break. */
public record LyricLine(long startMs, String text) {
	public boolean isGap() {
		return text == null || text.isBlank();
	}
}
