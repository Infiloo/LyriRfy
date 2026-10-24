package dev.lyrirfy.render;

import dev.lyrirfy.LyriRfy;
import dev.lyrirfy.config.Config;
import dev.lyrirfy.lyrics.LrcParser;
import dev.lyrirfy.lyrics.LyricsDoc;
import dev.lyrirfy.spotify.NowPlaying;
import dev.lyrirfy.spotify.SpotifyService;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * Draws the now playing widget and the flat (2D) lyrics. Both are positioned with
 * screen fractions so they survive window resizes and GUI scale changes.
 */
public final class HudOverlay implements HudElement {
	private static final int ACCENT = 0x1ED760;
	private static final int TEXT = 0xF2F2F5;
	private static final int SUBTLE = 0xA8A8B3;

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
		Minecraft minecraft = Minecraft.getInstance();
		Config config = LyriRfy.config();
		if (minecraft.player == null || minecraft.gui.hud.isHidden() || !config.enabled) {
			return;
		}
		SpotifyService spotify = LyriRfy.spotify();
		if (!spotify.isVisible(config)) {
			return;
		}
		ShakeState shake = LyriRfy.shake();
		long position = spotify.positionMs();
		OverlayData data = new OverlayData(spotify.nowPlaying(), spotify.lyrics(), position, spotify.hasArtwork());
		Font font = minecraft.font;

		if (config.enableHud) {
			float shakeX = config.shakeEnabled && config.shakeAffectsHud ? shake.offsetX() : 0.0F;
			float shakeY = config.shakeEnabled && config.shakeAffectsHud ? shake.offsetY() : 0.0F;
			drawHud(graphics, font, config, data, shakeX, shakeY);
		}
		if (config.enable2dLyrics) {
			float shakeX = config.shakeEnabled && config.shakeAffects2d ? shake.offsetX() : 0.0F;
			float shakeY = config.shakeEnabled && config.shakeAffects2d ? shake.offsetY() : 0.0F;
			drawLyrics2d(graphics, font, config, data, shakeX, shakeY, false);
		}
	}

	public record OverlayData(NowPlaying nowPlaying, LyricsDoc lyrics, long positionMs, boolean hasArtwork) {
	}

	// ================================================================== HUD

	public static void drawHud(GuiGraphicsExtractor graphics, Font font, Config config, OverlayData data,
			float shakeX, float shakeY) {
		NowPlaying track = data.nowPlaying();
		if (track == null) {
			return;
		}
		float scale = (float) config.hudScale;
		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();

		int pad = 5;
		int artSize = config.hudShowArtwork ? (int) Math.round(config.hudArtSize) : 0;
		int textWidth = artSize > 0 ? 165 : 195;
		int[] size = hudSize(config);
		int panelWidth = size[0];
		int panelHeight = size[1];

		float x = (float) (config.hudX * screenWidth);
		float y = (float) (config.hudY * screenHeight);
		x = Mth.clamp(x, 0.0F, Math.max(0.0F, screenWidth - panelWidth * scale));
		y = Mth.clamp(y, 0.0F, Math.max(0.0F, screenHeight - panelHeight * scale));

		int opacity = TextUtil.alphaInt(config.hudOpacity);
		int panelAlpha = (config.hudBackgroundAlpha * opacity) / 255;

		graphics.pose().pushMatrix();
		graphics.pose().translate(x + shakeX, y + shakeY);
		graphics.pose().scale(scale, scale);

		graphics.fill(0, 0, panelWidth, panelHeight, ARGB.color(panelAlpha, 8, 9, 13));
		graphics.fill(0, 0, 2, panelHeight, ARGB.color(200 * opacity / 255, ACCENT));
		graphics.outline(0, 0, panelWidth, panelHeight, ARGB.color(Math.min(255, panelAlpha + 55), 255, 255, 255));

		int textX = pad + (artSize > 0 ? artSize + 6 : 0);
		if (artSize > 0) {
			drawArtwork(graphics, font, data, pad, (panelHeight - artSize) / 2, artSize, opacity);
		}

		String title = TextUtil.truncate(font, track.title(), textWidth);
		String artist = TextUtil.truncate(font, track.artist(), textWidth);
		graphics.text(font, Component.literal(title).withStyle(ChatFormatting.BOLD), textX, pad, withAlpha(TEXT, opacity), true);
		graphics.text(font, artist, textX, pad + 11, withAlpha(SUBTLE, opacity), false);

		if (config.hudShowProgress) {
			int barY = pad + 25;
			int barHeight = 3;
			graphics.fill(textX, barY, textX + textWidth, barY + barHeight, withAlpha(0xFFFFFF, 55 * opacity / 255));
			double progress = track.durationMs() <= 0 ? 0.0
					: Mth.clamp(data.positionMs() / (double) track.durationMs(), 0.0, 1.0);
			int filled = (int) Math.round(textWidth * progress);
			if (filled > 0) {
				graphics.fill(textX, barY, textX + filled, barY + barHeight, withAlpha(ACCENT, opacity));
			}
			if (config.hudShowTime) {
				String time = TextUtil.formatTime(data.positionMs()) + " / " + TextUtil.formatTime(track.durationMs());
				graphics.text(font, time, textX, pad + 30, withAlpha(SUBTLE, (int) (opacity * 0.85)), false);
			}
		}
		graphics.pose().popMatrix();
	}

	private static void drawArtwork(GuiGraphicsExtractor graphics, Font font, OverlayData data, int x, int y, int size, int opacity) {
		if (data.hasArtwork()) {
			graphics.blit(RenderPipelines.GUI_TEXTURED, SpotifyService.ARTWORK_ID, x, y, 0.0F, 0.0F, size, size, size, size,
					ARGB.color(opacity, 255, 255, 255));
			return;
		}
		graphics.fill(x, y, x + size, y + size, ARGB.color(Math.min(255, 120 * opacity / 255), 28, 29, 36));
		graphics.outline(x, y, size, size, ARGB.color(Math.min(255, 190 * opacity / 255), 255, 255, 255));
		graphics.centeredText(font, "♪", x + size / 2, y + size / 2 - 4, withAlpha(ACCENT, opacity));
	}

	// ================================================================== 2D lyrics

	public static void drawLyrics2d(GuiGraphicsExtractor graphics, Font font, Config config, OverlayData data,
			float shakeX, float shakeY, boolean editor) {
		TextUtil.Frame frame = TextUtil.frame(data.lyrics(), data.positionMs(), config.lyrics2dFadeInMs,
				config.lyrics2dFadeOutMs, config.lyrics2dHoldMs, config.lyrics2dOpacity, config.lyrics2dShowNext);
		if (frame == null) {
			if (editor) {
				drawLyrics2dPlaceholder(graphics, font, config, shakeX, shakeY, editor);
			}
			return;
		}

		float scale = (float) config.lyrics2dScale;
		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();
		int maxWidth = Math.max(60, (int) (screenWidth * config.lyrics2dMaxWidth / scale));

		List<FormattedCharSequence> lines = TextUtil.wrap(font, frame.text(), maxWidth);
		int lineHeight = 10;
		int blockHeight = lines.size() * lineHeight;
		float centerX = (float) (config.lyrics2dX * screenWidth);
		float centerY = (float) (config.lyrics2dY * screenHeight) + frame.slide() * 7.0F;

		graphics.pose().pushMatrix();
		graphics.pose().translate(centerX + shakeX, centerY + shakeY);
		graphics.pose().scale(scale, scale);

		int alpha = TextUtil.alphaInt(frame.alpha());
		if (config.lyrics2dBackground) {
			int widest = 0;
			for (FormattedCharSequence line : lines) {
				widest = Math.max(widest, font.width(line));
			}
			int backgroundAlpha = (config.lyrics2dBackgroundAlpha * alpha) / 255;
			int padding = 6;
			graphics.fill(-widest / 2 - padding, -blockHeight / 2 - 4 - (lines.size() > 1 ? 1 : 0),
					widest / 2 + padding, blockHeight / 2 + 4, ARGB.color(backgroundAlpha, 6, 7, 10));
		}
		int color = withAlpha(0xFFFFFF, alpha);
		for (int i = 0; i < lines.size(); i++) {
			graphics.centeredText(font, lines.get(i), 0, -blockHeight / 2 + i * lineHeight, color);
		}

		if (frame.nextText() != null) {
			List<FormattedCharSequence> nextLines = TextUtil.wrap(font, frame.nextText(), maxWidth);
			int nextAlpha = (int) (alpha * 0.45);
			int nextY = blockHeight / 2 + 5;
			for (int i = 0; i < nextLines.size() && i < 2; i++) {
				graphics.centeredText(font, nextLines.get(i), 0, nextY + i * lineHeight, withAlpha(0xFFFFFF, nextAlpha));
			}
		}

		if (editor) {
			int widest = 0;
			for (FormattedCharSequence line : lines) {
				widest = Math.max(widest, font.width(line));
			}
			graphics.outline(-widest / 2 - 8, -blockHeight / 2 - 7, widest + 16, blockHeight + 14,
					ARGB.color(180, ACCENT));
			graphics.fill(-1, -2, 2, 2, ARGB.color(210, ACCENT));
		}
		graphics.pose().popMatrix();
	}

	private static void drawLyrics2dPlaceholder(GuiGraphicsExtractor graphics, Font font, Config config,
			float shakeX, float shakeY, boolean editor) {
		if (!editor) {
			return;
		}
		float scale = (float) config.lyrics2dScale;
		int screenWidth = graphics.guiWidth();
		int screenHeight = graphics.guiHeight();
		String text = "Lyrics show up here";
		int width = font.width(text);

		graphics.pose().pushMatrix();
		graphics.pose().translate((float) (config.lyrics2dX * screenWidth) + shakeX,
				(float) (config.lyrics2dY * screenHeight) + shakeY);
		graphics.pose().scale(scale, scale);
		graphics.centeredText(font, text, 0, -4, ARGB.color(150, 255, 255, 255));
		graphics.outline(-width / 2 - 8, -11, width + 16, 22, ARGB.color(180, ACCENT));
		graphics.fill(-1, -2, 2, 2, ARGB.color(210, ACCENT));
		graphics.pose().popMatrix();
	}

	/** Unscaled size of the HUD widget, used by the drag editor. */
	public static int[] hudSize(Config config) {
		int pad = 5;
		int artSize = config.hudShowArtwork ? (int) Math.round(config.hudArtSize) : 0;
		int textWidth = artSize > 0 ? 165 : 195;
		int panelWidth = pad + (artSize > 0 ? artSize + 6 : 0) + textWidth + pad;
		int panelHeight = Math.max(artSize, 40) + pad * 2;
		return new int[] { panelWidth, panelHeight };
	}

	public static OverlayData previewData(Config config) {
		NowPlaying track = new NowPlaying("preview", "Midnight City", "M83", "Hurry Up, We're Dreaming",
				241_000L, 58_000L, null, true, true);
		LyricsDoc lyrics = LrcParser.parse("[00:00.00]Live lyrics float right here\n[00:02.00]Sing along while you play", 241_000L);
		return new OverlayData(track, lyrics, 2_500L, LyriRfy.spotify() != null && LyriRfy.spotify().hasArtwork());
	}

	private static int withAlpha(int rgb, int alpha) {
		return ARGB.color(Mth.clamp(alpha, 0, 255), rgb);
	}
}
