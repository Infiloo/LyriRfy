package dev.lyrirfy.lyrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the LRC format used by LRCLIB. Handles multiple timestamps per line,
 * the [offset:...] tag, and enhanced word-by-word tags (which are stripped).
 */
public final class LrcParser {
	private static final Pattern TIMESTAMP = Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]");
	private static final Pattern OFFSET = Pattern.compile("\\[offset:\\s*([+-]?\\d+)\\s*]", Pattern.CASE_INSENSITIVE);
	private static final Pattern METADATA = Pattern.compile("^\\[[a-zA-Z]+:.*]$");
	private static final Pattern ENHANCED = Pattern.compile("<\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?>");
	private static final Pattern WORD_TAGS = Pattern.compile("<[^>]{0,24}>");

	private LrcParser() {
	}

	public static LyricsDoc parse(String lrc, long trackDurationMs) {
		List<LyricLine> lines = new ArrayList<>();
		if (lrc == null || lrc.isBlank()) {
			return new LyricsDoc(lines, trackDurationMs, "empty");
		}

		long offset = 0L;
		Matcher offsetMatcher = OFFSET.matcher(lrc);
		if (offsetMatcher.find()) {
			try {
				offset = Long.parseLong(offsetMatcher.group(1));
			} catch (NumberFormatException ignored) {
				offset = 0L;
			}
		}

		for (String rawLine : lrc.split("\\R")) {
			String line = rawLine.trim();
			if (line.isEmpty()) {
				continue;
			}
			Matcher matcher = TIMESTAMP.matcher(line);
			List<Long> stamps = new ArrayList<>(2);
			int lastEnd = 0;
			while (matcher.find()) {
				long minutes = Long.parseLong(matcher.group(1));
				long seconds = Long.parseLong(matcher.group(2));
				String fraction = matcher.group(3);
				long millis = 0L;
				if (fraction != null) {
					millis = Long.parseLong(fraction);
					if (fraction.length() == 1) {
						millis *= 100L;
					} else if (fraction.length() == 2) {
						millis *= 10L;
					} else if (fraction.length() > 3) {
						millis /= (long) Math.pow(10, fraction.length() - 3);
					}
				}
				stamps.add(minutes * 60_000L + seconds * 1000L + millis + offset);
				lastEnd = matcher.end();
			}
			if (stamps.isEmpty()) {
				if (METADATA.matcher(line).matches()) {
					continue;
				}
				// A completely untimed line: treat as a gap marker at the end.
				continue;
			}

			String text = line.substring(lastEnd).trim();
			text = ENHANCED.matcher(text).replaceAll("");
			text = WORD_TAGS.matcher(text).replaceAll("");
			text = sanitize(text);
			for (long stamp : stamps) {
				lines.add(new LyricLine(Math.max(0L, stamp), text));
			}
		}

		lines.sort(Comparator.comparingLong(LyricLine::startMs));
		// Collapse duplicate timestamps, preferring a line with actual text.
		List<LyricLine> deduped = new ArrayList<>(lines.size());
		for (LyricLine line : lines) {
			if (!deduped.isEmpty() && deduped.get(deduped.size() - 1).startMs() == line.startMs()) {
				LyricLine previous = deduped.get(deduped.size() - 1);
				if (previous.isGap() && !line.isGap()) {
					deduped.set(deduped.size() - 1, line);
				}
				continue;
			}
			deduped.add(line);
		}
		return new LyricsDoc(deduped, trackDurationMs, "lrclib");
	}

	/** Minecraft's font has no glyphs for most control characters; drop section signs and the like. */
	private static String sanitize(String text) {
		if (text.isEmpty()) {
			return text;
		}
		StringBuilder out = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			if (c == '\u00a7' || c == '\r' || c == '\n' || c == '\t') {
				continue;
			}
			out.append(c);
		}
		return out.toString().trim();
	}
}
