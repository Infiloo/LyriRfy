package dev.lyrirfy.ui;

import dev.lyrirfy.LyriRfy;
import dev.lyrirfy.config.Config;
import dev.lyrirfy.render.HudOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

/**
 * Drag & drop editor for the HUD and the 2D lyrics. Positions are stored as screen
 * fractions, so they stay correct when the window or GUI scale changes.
 */
public class LayoutEditorScreen extends Screen {
	public enum Target {
		HUD, LYRICS_2D
	}

	private final Target target;
	private final Config config;
	private boolean dragging;
	private double grabOffsetX;
	private double grabOffsetY;

	public LayoutEditorScreen(Target target) {
		super(Component.literal("Move LyriRfy element"));
		this.target = target;
		this.config = LyriRfy.config();
	}

	@Override
	protected void init() {
		super.init();
		int center = width / 2;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(center + 4, height - 26, 100, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset position"), b -> {
			if (target == Target.HUD) {
				Config defaults = new Config();
				config.hudX = defaults.hudX;
				config.hudY = defaults.hudY;
			} else {
				Config defaults = new Config();
				config.lyrics2dX = defaults.lyrics2dX;
				config.lyrics2dY = defaults.lyrics2dY;
			}
			LyriRfy.markDirty();
		}).bounds(center - 104, height - 26, 100, 20).build());
		if (target == Target.LYRICS_2D) {
			addRenderableWidget(Button.builder(Component.literal("Center"), b -> {
				config.lyrics2dX = 0.5;
				LyriRfy.markDirty();
			}).bounds(center - 190, height - 26, 80, 20).build());
		}
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		graphics.fill(0, 0, width, height, 0x8A05060A);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		for (int i = 1; i < 10; i++) {
			int x = width * i / 10;
			int y = height * i / 10;
			graphics.fill(x, 0, x + 1, height, 0x22FFFFFF);
			graphics.fill(0, y, width, y + 1, 0x22FFFFFF);
		}
		graphics.fill(width / 2, 0, width / 2 + 1, height, 0x55FFFFFF);
		graphics.fill(0, height / 2, width, height / 2 + 1, 0x55FFFFFF);

		HudOverlay.OverlayData data = data();
		if (target == Target.HUD) {
			HudOverlay.drawHud(graphics, font, config, data, 0.0F, 0.0F);
			int[] size = HudOverlay.hudSize(config);
			float scale = (float) config.hudScale;
			int elementWidth = Math.round(size[0] * scale);
			int elementHeight = Math.round(size[1] * scale);
			int x = Mth.clamp((int) (config.hudX * width), 0, Math.max(0, width - elementWidth));
			int y = Mth.clamp((int) (config.hudY * height), 0, Math.max(0, height - elementHeight));
			graphics.outline(x - 2, y - 2, elementWidth + 4, elementHeight + 4, ARGB.color(220, 30, 215, 96));
			graphics.fill(x - 3, y - 3, x + 3, y + 3, ARGB.color(240, 30, 215, 96));
		} else {
			HudOverlay.drawLyrics2d(graphics, font, config, data, 0.0F, 0.0F, true);
		}

		graphics.centeredText(font, Component.literal(target == Target.HUD ? "Drag the HUD where you want it"
						: "Drag the lyrics where you want them"),
				width / 2, 12, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal("Arrow keys: fine tune  •  Shift + arrows: 10x  •  Scroll: scale  •  Esc: back"),
				width / 2, 24, 0xFF9A9AA6);

		String position = target == Target.HUD
				? String.format("x %.1f%%   y %.1f%%   scale %.2fx", config.hudX * 100, config.hudY * 100, config.hudScale)
				: String.format("x %.1f%%   y %.1f%%   scale %.2fx", config.lyrics2dX * 100, config.lyrics2dY * 100, config.lyrics2dScale);
		graphics.text(font, position, 12, height - 42, 0xFFB8B8C4);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);
	}

	private HudOverlay.OverlayData data() {
		var spotify = LyriRfy.spotify();
		if (spotify != null && spotify.isVisible(config)) {
			return new HudOverlay.OverlayData(spotify.nowPlaying(), spotify.lyrics(), spotify.positionMs(), spotify.hasArtwork());
		}
		return HudOverlay.previewData(config);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (event.button() != 0) {
			return false;
		}
		double mouseX = event.x();
		double mouseY = event.y();
		if (target == Target.HUD) {
			int[] size = HudOverlay.hudSize(config);
			float scale = (float) config.hudScale;
			int elementWidth = Math.round(size[0] * scale);
			int elementHeight = Math.round(size[1] * scale);
			int x = Mth.clamp((int) (config.hudX * width), 0, Math.max(0, width - elementWidth));
			int y = Mth.clamp((int) (config.hudY * height), 0, Math.max(0, height - elementHeight));
			if (mouseX >= x - 4 && mouseX <= x + elementWidth + 4 && mouseY >= y - 4 && mouseY <= y + elementHeight + 4) {
				dragging = true;
				grabOffsetX = mouseX - x;
				grabOffsetY = mouseY - y;
				return true;
			}
		} else {
			float scale = (float) config.lyrics2dScale;
			double centerX = config.lyrics2dX * width;
			double centerY = config.lyrics2dY * height;
			double halfWidth = Math.max(120, width * config.lyrics2dMaxWidth * 0.5);
			double halfHeight = 32 * scale;
			if (Math.abs(mouseX - centerX) <= halfWidth && Math.abs(mouseY - centerY) <= halfHeight) {
				dragging = true;
				grabOffsetX = mouseX - centerX;
				grabOffsetY = mouseY - centerY;
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!dragging) {
			return super.mouseDragged(event, dragX, dragY);
		}
		if (target == Target.HUD) {
			int[] size = HudOverlay.hudSize(config);
			float scale = (float) config.hudScale;
			int elementWidth = Math.round(size[0] * scale);
			int elementHeight = Math.round(size[1] * scale);
			int maxX = Math.max(0, width - elementWidth);
			int maxY = Math.max(0, height - elementHeight);
			double x = Mth.clamp(event.x() - grabOffsetX, 0.0, maxX);
			double y = Mth.clamp(event.y() - grabOffsetY, 0.0, maxY);
			if (x < 8) {
				x = 0;
			} else if (x > maxX - 8) {
				x = maxX;
			}
			if (y < 8) {
				y = 0;
			} else if (y > maxY - 8) {
				y = maxY;
			}
			config.hudX = width <= 0 ? 0.0 : x / width;
			config.hudY = height <= 0 ? 0.0 : y / height;
		} else {
			double x = Mth.clamp(event.x() - grabOffsetX, 0.0, width);
			double y = Mth.clamp(event.y() - grabOffsetY, 12.0, height - 12.0);
			double fractionX = width <= 0 ? 0.5 : x / width;
			if (Math.abs(x - width / 2.0) < 10.0) {
				fractionX = 0.5;
			}
			if (fractionX < 0.02) {
				fractionX = 0.0;
			} else if (fractionX > 0.98) {
				fractionX = 1.0;
			}
			config.lyrics2dX = fractionX;
			config.lyrics2dY = height <= 0 ? 0.5 : y / height;
		}
		LyriRfy.markDirty();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		dragging = false;
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (target == Target.HUD) {
			config.hudScale = Mth.clamp(config.hudScale + scrollY * 0.05, 0.5, 2.5);
		} else {
			config.lyrics2dScale = Mth.clamp(config.lyrics2dScale + scrollY * 0.05, 0.4, 3.0);
		}
		LyriRfy.markDirty();
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.input();
		double step = event.hasShiftDown() ? 0.02 : 0.002;
		boolean handled = true;
		switch (key) {
			case GLFW.GLFW_KEY_LEFT -> move(-step, 0);
			case GLFW.GLFW_KEY_RIGHT -> move(step, 0);
			case GLFW.GLFW_KEY_UP -> move(0, -step);
			case GLFW.GLFW_KEY_DOWN -> move(0, step);
			default -> handled = false;
		}
		if (handled) {
			LyriRfy.markDirty();
			return true;
		}
		return super.keyPressed(event);
	}

	private void move(double dx, double dy) {
		if (target == Target.HUD) {
			config.hudX = Mth.clamp(config.hudX + dx, 0.0, 1.0);
			config.hudY = Mth.clamp(config.hudY + dy, 0.0, 1.0);
		} else {
			config.lyrics2dX = Mth.clamp(config.lyrics2dX + dx, 0.0, 1.0);
			config.lyrics2dY = Mth.clamp(config.lyrics2dY + dy, 0.0, 1.0);
		}
	}

	@Override
	public void onClose() {
		LyriRfy.saveConfigNow();
		if (minecraft != null) {
			minecraft.gui.setScreen(new ConfigScreen());
		}
	}
}
