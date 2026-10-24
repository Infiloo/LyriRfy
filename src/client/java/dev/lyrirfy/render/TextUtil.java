package dev.lyrirfy.render;

import dev.lyrirfy.lyrics.LyricsDoc;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

public final class TextUtil {
	private TextUtil() {
	}

	public static List<FormattedCharSequence> wrap(Font font, String text, int maxWidth) {
		List<FormattedCharSequence> lines = font.split(Component.literal(text), Math.max(20, maxWidth));
		if (lines.isEmpty()) {
			lines = List.of(Component.literal(text).getVisualOrderText());
		}
		return lines;
	}

	public static String formatTime(long millis) {
		long totalSeconds = Math.max(0L, millis / 1000L);
		long minutes = totalSeconds / 60L;
		long seconds = totalSeconds % 60L;
		return minutes + ":" + (seconds < 10 ? "0" + seconds : Long.toString(seconds));
	}

	public static String truncate(Font font, String text, int maxWidth) {
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		int width = maxWidth - font.width(ellipsis);
		if (width <= 0) {
			return ellipsis;
		}
		String shortened = font.plainSubstrByWidth(text, width);
		return shortened + ellipsis;
	}

	public record Frame(LyricsDoc doc, int index, String text, String nextText, float alpha, float slide, float entrance) {
	}

	/** Finds the line that should be visible right now, including fade in / fade out. */
	public static Frame frame(LyricsDoc doc, long positionMs, int fadeInMs, int fadeOutMs, int holdMs, double baseAlpha,
			boolean includeNext) {
		if (doc == null || doc.isEmpty() || baseAlpha <= 0.001) {
			return null;
		}
		int index = doc.indexAt(positionMs);
		if (index < 0) {
			return null;
		}
		var line = doc.line(index);
		if (line == null || line.isGap()) {
			return null;
		}
		long start = line.startMs();
		long end = doc.endOf(index, holdMs);
		if (positionMs < start || positionMs >= end) {
			return null;
		}

		float fadeIn = fadeInMs <= 0 ? 1.0F : clamp01((positionMs - start) / (float) fadeInMs);
		float fadeOut = fadeOutMs <= 0 ? 1.0F : clamp01((end - positionMs) / (float) fadeOutMs);
		float alpha = Math.min(fadeIn, fadeOut) * (float) baseAlpha;
		if (alpha <= 0.001F) {
			return null;
		}
		float eased = easeOutCubic(fadeIn);
		float slide = (1.0F - eased) * 0.35F;

		String nextText = null;
		if (includeNext && index + 1 < doc.lines().size()) {
			var next = doc.lines().get(index + 1);
			if (next != null && !next.isGap()) {
				nextText = next.text();
			}
		}
		return new Frame(doc, index, line.text(), nextText, alpha, slide, eased);
	}

	public static float clamp01(float value) {
		return Math.max(0.0F, Math.min(1.0F, value));
	}

	public static float easeOutCubic(float t) {
		float inverse = 1.0F - clamp01(t);
		return 1.0F - inverse * inverse * inverse;
	}

	public static int alphaInt(double alpha) {
		return (int) (Math.max(0.0, Math.min(1.0, alpha)) * 255.0 + 0.5);
	}
}
