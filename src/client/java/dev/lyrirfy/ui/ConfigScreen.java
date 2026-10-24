package dev.lyrirfy.ui;

import dev.lyrirfy.LyriRfy;
import dev.lyrirfy.config.Config;
import dev.lyrirfy.spotify.SpotifyService;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * The full configuration UI. Every visual effect has a slider so its strength can be
 * tuned to taste, and the Spotify credentials can be entered right in game.
 */
public class ConfigScreen extends Screen {
	private static final String[] TAB_NAMES = { "Spotify", "3D lyrics", "HUD", "2D lyrics", "Shake", "General" };
	private static final int SIDE = 16;
	private static final int GAP = 10;
	private static final int TOP = 54;
	private static final int ROW_HEIGHT = 20;

	private final List<Label> labels = new ArrayList<>();
	private final List<Wrapped> wrapped = new ArrayList<>();
	private int tab;
	private int[] tabX = new int[0];
	private int[] tabWidth = new int[0];
	private int tabY;
	private int statusY;
	private int trackY;
	private EditBox clientIdBox;
	private EditBox clientSecretBox;
	private EditBox redirectUriBox;
	private EditBox pasteBox;
	private String spotifyNotice = "";
	private int spotifyHintOffset = 23;
	private long lastSpotifyRefresh;

	private record Label(String text, int x, int y, int color, boolean shadow) {
	}

	private record Wrapped(String text, int x, int y, int width, int color) {
	}

	public ConfigScreen() {
		super(Component.literal("LyriRfy"));
		this.tab = Mth.clamp(LyriRfy.config().lastTab, 0, TAB_NAMES.length - 1);
	}

	@Override
	protected void init() {
		super.init();
		labels.clear();
		wrapped.clear();
		tab = Mth.clamp(tab, 0, TAB_NAMES.length - 1);
		buildTabs();
		buildFooter();
		switch (tab) {
			case 0 -> buildSpotify();
			case 1 -> build3d();
			case 2 -> buildHud();
			case 3 -> build2d();
			case 4 -> buildShake();
			default -> buildGeneral();
		}
	}

	// ================================================================== chrome

	private void buildTabs() {
		tabY = 26;
		int count = TAB_NAMES.length;
		int available = width - SIDE * 2;
		int buttonWidth = Math.min(104, (available - (count - 1) * 4) / count);
		int total = buttonWidth * count + (count - 1) * 4;
		int startX = (width - total) / 2;
		tabX = new int[count];
		tabWidth = new int[count];
		for (int i = 0; i < count; i++) {
			final int index = i;
			tabX[i] = startX + i * (buttonWidth + 4);
			tabWidth[i] = buttonWidth;
			Button button = Button.builder(Component.literal(TAB_NAMES[i]), b -> {
				tab = index;
				LyriRfy.config().lastTab = index;
				LyriRfy.markDirty();
				rebuildWidgets();
			}).bounds(tabX[i], tabY, buttonWidth, 18).build();
			addRenderableWidget(button);
		}
	}

	private void buildFooter() {
		int y = height - 26;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(width - SIDE - 90, y, 90, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Reset page"), b -> {
			resetCurrentTab();
			rebuildWidgets();
		}).bounds(SIDE, y, 100, 20).build());
	}

	@Override
	public void onClose() {
		LyriRfy.saveConfigNow();
		super.onClose();
	}

	// ================================================================== layout helpers

	private final class Columns {
		final int columns;
		final int columnWidth;
		final int[] y;

		Columns() {
			columns = height < 340 ? 3 : 2;
			columnWidth = Math.max(70, (width - SIDE * 2 - GAP * (columns - 1)) / columns);
			y = new int[columns];
			for (int i = 0; i < columns; i++) {
				y[i] = TOP;
			}
		}

		void add(int rowHeight, boolean fullWidth, Placement placement) {
			int x;
			int yPos;
			int w;
			if (fullWidth) {
				yPos = maxY();
				x = SIDE;
				w = columnWidth * columns + GAP * (columns - 1);
				for (int i = 0; i < columns; i++) {
					y[i] = yPos + rowHeight + 4;
				}
			} else {
				int column = 0;
				for (int i = 1; i < columns; i++) {
					if (y[i] < y[column]) {
						column = i;
					}
				}
				x = SIDE + column * (columnWidth + GAP);
				yPos = y[column];
				w = columnWidth;
				y[column] = yPos + rowHeight + 4;
			}
			placement.place(x, yPos, w);
		}

		private int maxY() {
			int max = y[0];
			for (int i = 1; i < y.length; i++) {
				max = Math.max(max, y[i]);
			}
			return max;
		}
	}

	private interface Placement {
		void place(int x, int y, int width);
	}

	private void slider(Columns columns, String label, double min, double max, double value, double step,
			DoubleFunction<String> formatter, DoubleConsumer setter) {
		columns.add(ROW_HEIGHT, false, (x, y, w) ->
				addRenderableWidget(new OptionSlider(x, y, w, ROW_HEIGHT, label, min, max, value, step, formatter, setter)));
	}

	private void toggle(Columns columns, String label, boolean value, Consumer<Boolean> setter) {
		columns.add(ROW_HEIGHT, false, (x, y, w) -> {
			final boolean[] state = { value };
			Button button = Button.builder(toggleLabel(label, value), b -> {
				state[0] = !state[0];
				setter.accept(state[0]);
				b.setMessage(toggleLabel(label, state[0]));
				LyriRfy.markDirty();
			}).bounds(x, y, w, ROW_HEIGHT).build();
			addRenderableWidget(button);
		});
	}

	private void action(Columns columns, String label, Runnable action) {
		columns.add(ROW_HEIGHT, false, (x, y, w) ->
				addRenderableWidget(Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, ROW_HEIGHT).build()));
	}

	private void field(Columns columns, String label, String value, int maxLength, Consumer<String> setter) {
		columns.add(32, true, (x, y, w) -> {
			labels.add(new Label(label, x, y, 0xFFB8B8C4, false));
			EditBox box = new EditBox(font, x, y + 12, w, 18, Component.literal(label));
			box.setMaxLength(maxLength);
			box.setValue(value == null ? "" : value);
			box.setHint(Component.literal(label).withStyle(ChatFormatting.DARK_GRAY));
			box.setResponder(text -> {
				setter.accept(text);
				LyriRfy.markDirty();
			});
			addRenderableWidget(box);
		});
	}

	private static Component toggleLabel(String label, boolean on) {
		return Component.literal(label + ": ").append(Component.literal(on ? "ON" : "OFF")
				.withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	// ================================================================== tabs

	private void buildSpotify() {
		Config config = LyriRfy.config();
		SpotifyService spotify = LyriRfy.spotify();
		int left = SIDE;
		int right = width - SIDE;
		int fullWidth = right - left;
		// Small windows (and the default auto GUI scale on 1080p) leave very little
		// vertical room, so the explanatory text is dropped there.
		boolean compact = height < 340;
		boolean ultra = height < 260;
		int labelStep = compact ? 9 : 11;
		int boxStep = ultra ? 20 : compact ? 22 : 23;
		int y = ultra ? 50 : TOP;

		if (!compact) {
			wrapped.add(new Wrapped("Create an app at developer.spotify.com/dashboard and add the Redirect URI below "
					+ "to its Redirect URIs. Then paste your Client ID here and press Connect.",
					left, y, fullWidth, 0xFFB0B0BC));
			y += 26;
		}

		labels.add(new Label("Redirect URI (must match the Spotify dashboard exactly)", left, y, 0xFFB8B8C4, false));
		y += labelStep;
		int copyWidth = 74;
		redirectUriBox = new EditBox(font, left, y, fullWidth - copyWidth - 6, 18, Component.literal("Redirect URI"));
		redirectUriBox.setMaxLength(300);
		redirectUriBox.setValue(config.redirectUri());
		redirectUriBox.setHint(Component.literal("https://gravatar.me/infiloo"));
		redirectUriBox.setResponder(text -> {
			config.redirectUri = text.trim();
			LyriRfy.markDirty();
		});
		addRenderableWidget(redirectUriBox);
		addRenderableWidget(Button.builder(Component.literal("Copy"), b -> {
			config.sanitize();
			redirectUriBox.setValue(config.redirectUri());
			minecraft.keyboardHandler.setClipboard(config.redirectUri());
		}).bounds(right - copyWidth, y, copyWidth, 18).build());
		y += boxStep;

		if (!compact) {
			locationsHint(y, left, fullWidth, config);
			y += 14;
		}

		labels.add(new Label("Client ID", left, y, 0xFFB8B8C4, false));
		y += labelStep;
		clientIdBox = new EditBox(font, left, y, fullWidth, 18, Component.literal("Client ID"));
		clientIdBox.setMaxLength(200);
		clientIdBox.setValue(config.clientId);
		clientIdBox.setHint(Component.literal("Paste the Client ID from your Spotify app"));
		clientIdBox.setResponder(text -> {
			config.clientId = text.trim();
			LyriRfy.markDirty();
		});
		addRenderableWidget(clientIdBox);
		y += boxStep;

		labels.add(new Label("Client Secret (optional for PKCE apps)", left, y, 0xFFB8B8C4, false));
		y += labelStep;
		clientSecretBox = new EditBox(font, left, y, fullWidth, 18, Component.literal("Client Secret"));
		clientSecretBox.setMaxLength(200);
		clientSecretBox.setValue(config.clientSecret);
		clientSecretBox.setHint(Component.literal("Only needed if your app is not a public PKCE app"));
		clientSecretBox.setResponder(text -> {
			config.clientSecret = text.trim();
			LyriRfy.markDirty();
		});
		addRenderableWidget(clientSecretBox);
		y += ultra ? 20 : compact ? 23 : 24;

		int connectWidth = Math.min(150, Math.max(96, (int) (fullWidth * 0.38)));
		int disconnectWidth = Math.min(110, Math.max(74, (int) (fullWidth * 0.22)));
		int refreshWidth = Math.max(64, fullWidth - connectWidth - disconnectWidth - 12);
		boolean connected = spotify != null && spotify.isConnected();
		addRenderableWidget(Button.builder(Component.literal(connected ? "Reconnect" : "Connect with Spotify"), b -> {
			spotifyNotice = "";
			config.sanitize();
			redirectUriBox.setValue(config.redirectUri());
			LyriRfy.spotify().connect();
		}).bounds(left, y, connectWidth, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Disconnect"), b -> {
			spotifyNotice = "";
			LyriRfy.spotify().disconnect();
		}).bounds(left + connectWidth + 6, y, disconnectWidth, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> LyriRfy.spotify().refreshNow())
				.bounds(left + connectWidth + disconnectWidth + 12, y, refreshWidth, 18).build());
		y += ultra ? 20 : compact ? 22 : 24;

		if (!compact) {
			labels.add(new Label("Already approved? Paste the full redirect URL here:", left, y, 0xFFB8B8C4, false));
			y += 11;
		}
		int finishWidth = 96;
		pasteBox = new EditBox(font, left, y, fullWidth - finishWidth - 6, 18,
				Component.literal("Redirect URL"));
		pasteBox.setMaxLength(4096);
		pasteBox.setHint(Component.literal("Paste redirect URL or code to finish login"));
		pasteBox.setResponder(text -> spotifyNotice = "");
		addRenderableWidget(pasteBox);
		addRenderableWidget(Button.builder(Component.literal("Finish login"), b -> {
			String result = spotify == null ? "Spotify is not initialised." : spotify.submitRedirectInput(pasteBox.getValue());
			if (result == null || result.isEmpty()) {
				pasteBox.setValue("");
				spotifyNotice = "Login accepted, fetching your player…";
			} else {
				spotifyNotice = result;
			}
		}).bounds(right - finishWidth, y, finishWidth, 18).build());
		y += ultra ? 20 : compact ? 22 : 26;

		statusY = y;
		trackY = statusY + (ultra ? 14 : compact ? 16 : 24);
		spotifyHintOffset = ultra ? 9 : compact ? 12 : 23;
	}

	/** One compact line that explains where the callback can actually be captured. */
	private void locationsHint(int y, int x, int width, Config config) {
		String hint = config.isLoopbackRedirect()
				? "Local callback: the mod listens on 127.0.0.1 and finishes the login automatically."
				: "HTTPS redirect: approve in the browser, then paste the full URL it redirects to below.";
		int color = config.isLoopbackRedirect() ? 0xFF7BE495 : 0xFF9A9AA6;
		wrapped.add(new Wrapped(hint, x, y - 2, width, color));
	}

	private void build3d() {
		Config config = LyriRfy.config();
		Columns columns = new Columns();
		toggle(columns, "3D world lyrics", config.enable3d, value -> config.enable3d = value);
		toggle(columns, "Fixed 3D", config.lyrics3dFixed, value -> config.lyrics3dFixed = value);
		toggle(columns, "Fixed 3D faces you", config.lyrics3dFixedFacePlayer, value -> config.lyrics3dFixedFacePlayer = value);
		toggle(columns, "Avoid walls", config.lyrics3dAvoidBlocks, value -> config.lyrics3dAvoidBlocks = value);
		toggle(columns, "Show next line", config.lyrics3dShowNext, value -> config.lyrics3dShowNext = value);
		slider(columns, "Distance", 2.0, 20.0, config.lyrics3dDistance, 0.5,
				value -> String.format("%.1f blocks", value), value -> config.lyrics3dDistance = value);
		slider(columns, "Height", -4.0, 2.0, config.lyrics3dHeight, 0.1,
				value -> String.format("%+.2f", value), value -> config.lyrics3dHeight = value);
		slider(columns, "Left / right", -5.0, 5.0, config.lyrics3dLateral, 0.1,
				value -> String.format("%+.2f", value), value -> config.lyrics3dLateral = value);
		slider(columns, "Text size", 0.4, 3.0, config.lyrics3dScale, 0.05,
				value -> String.format("%.2fx", value), value -> config.lyrics3dScale = value);
		slider(columns, "Opacity", 0.1, 1.0, config.lyrics3dOpacity, 0.05,
				value -> Math.round(value * 100) + "%", value -> config.lyrics3dOpacity = value);
		slider(columns, "Fade in", 0, 1500, config.lyrics3dFadeInMs, 25,
				value -> Math.round(value) + " ms", value -> config.lyrics3dFadeInMs = (int) Math.round(value));
		slider(columns, "Fade out", 0, 2000, config.lyrics3dFadeOutMs, 25,
				value -> Math.round(value) + " ms", value -> config.lyrics3dFadeOutMs = (int) Math.round(value));
		slider(columns, "Max line hold", 1000, 15000, config.lyrics3dHoldMs, 250,
				value -> Math.round(value) + " ms", value -> config.lyrics3dHoldMs = (int) Math.round(value));
		slider(columns, "Line width", 80, 400, config.lyrics3dMaxWidth, 10,
				value -> Math.round(value) + " px", value -> config.lyrics3dMaxWidth = (int) Math.round(value));
		slider(columns, "Background", 0, 255, config.lyrics3dBackgroundAlpha, 5,
				value -> Math.round(value * 100 / 255) + "%", value -> config.lyrics3dBackgroundAlpha = (int) Math.round(value));
		slider(columns, "Float amount", 0.0, 0.4, config.lyrics3dBobAmount, 0.01,
				value -> String.format("%.2f", value), value -> config.lyrics3dBobAmount = value);
		slider(columns, "Float speed", 0.0, 3.0, config.lyrics3dBobSpeed, 0.1,
				value -> String.format("%.1f", value), value -> config.lyrics3dBobSpeed = value);
		action(columns, "Preview in world (closes menu)", () -> minecraft.gui.setScreen(null));
	}

	private void buildHud() {
		Config config = LyriRfy.config();
		Columns columns = new Columns();
		toggle(columns, "Now playing HUD", config.enableHud, value -> config.enableHud = value);
		toggle(columns, "Artwork", config.hudShowArtwork, value -> config.hudShowArtwork = value);
		toggle(columns, "Progress bar", config.hudShowProgress, value -> config.hudShowProgress = value);
		toggle(columns, "Time text", config.hudShowTime, value -> config.hudShowTime = value);
		slider(columns, "Scale", 0.5, 2.5, config.hudScale, 0.05,
				value -> String.format("%.2fx", value), value -> config.hudScale = value);
		slider(columns, "Opacity", 0.1, 1.0, config.hudOpacity, 0.05,
				value -> Math.round(value * 100) + "%", value -> config.hudOpacity = value);
		slider(columns, "Artwork size", 16, 96, config.hudArtSize, 2,
				value -> Math.round(value) + " px", value -> config.hudArtSize = value);
		slider(columns, "Panel darkness", 0, 255, config.hudBackgroundAlpha, 5,
				value -> Math.round(value * 100 / 255) + "%", value -> config.hudBackgroundAlpha = (int) Math.round(value));
		action(columns, "Move HUD…", () -> {
			LyriRfy.saveConfigNow();
			minecraft.gui.setScreen(new LayoutEditorScreen(LayoutEditorScreen.Target.HUD));
		});
	}

	private void build2d() {
		Config config = LyriRfy.config();
		Columns columns = new Columns();
		toggle(columns, "2D screen lyrics", config.enable2dLyrics, value -> config.enable2dLyrics = value);
		toggle(columns, "Background", config.lyrics2dBackground, value -> config.lyrics2dBackground = value);
		toggle(columns, "Show next line", config.lyrics2dShowNext, value -> config.lyrics2dShowNext = value);
		slider(columns, "Scale", 0.4, 3.0, config.lyrics2dScale, 0.05,
				value -> String.format("%.2fx", value), value -> config.lyrics2dScale = value);
		slider(columns, "Opacity", 0.1, 1.0, config.lyrics2dOpacity, 0.05,
				value -> Math.round(value * 100) + "%", value -> config.lyrics2dOpacity = value);
		slider(columns, "Max width", 0.2, 1.0, config.lyrics2dMaxWidth, 0.02,
				value -> Math.round(value * 100) + "%", value -> config.lyrics2dMaxWidth = value);
		slider(columns, "Background strength", 0, 255, config.lyrics2dBackgroundAlpha, 5,
				value -> Math.round(value * 100 / 255) + "%", value -> config.lyrics2dBackgroundAlpha = (int) Math.round(value));
		slider(columns, "Fade in", 0, 1500, config.lyrics2dFadeInMs, 25,
				value -> Math.round(value) + " ms", value -> config.lyrics2dFadeInMs = (int) Math.round(value));
		slider(columns, "Fade out", 0, 2000, config.lyrics2dFadeOutMs, 25,
				value -> Math.round(value) + " ms", value -> config.lyrics2dFadeOutMs = (int) Math.round(value));
		slider(columns, "Max line hold", 1000, 15000, config.lyrics2dHoldMs, 250,
				value -> Math.round(value) + " ms", value -> config.lyrics2dHoldMs = (int) Math.round(value));
		action(columns, "Move 2D lyrics…", () -> {
			LyriRfy.saveConfigNow();
			minecraft.gui.setScreen(new LayoutEditorScreen(LayoutEditorScreen.Target.LYRICS_2D));
		});
	}

	private void buildShake() {
		Config config = LyriRfy.config();
		Columns columns = new Columns();
		toggle(columns, "Screen shake", config.shakeEnabled, value -> config.shakeEnabled = value);
		toggle(columns, "Shake HUD", config.shakeAffectsHud, value -> config.shakeAffectsHud = value);
		toggle(columns, "Shake 2D lyrics", config.shakeAffects2d, value -> config.shakeAffects2d = value);
		slider(columns, "Strength", 0.0, 3.0, config.shakeStrength, 0.05,
				value -> String.format("%.2fx", value), value -> config.shakeStrength = value);
		slider(columns, "Jump / fall", 0.0, 3.0, config.shakeJumpSensitivity, 0.05,
				value -> String.format("%.2fx", value), value -> config.shakeJumpSensitivity = value);
		slider(columns, "Camera speed", 0.0, 3.0, config.shakeCameraSensitivity, 0.05,
				value -> String.format("%.2fx", value), value -> config.shakeCameraSensitivity = value);
		slider(columns, "Decay", 0.5, 12.0, config.shakeDecay, 0.1,
				value -> String.format("%.1f", value), value -> config.shakeDecay = value);
		slider(columns, "Frequency", 2.0, 40.0, config.shakeFrequency, 0.5,
				value -> String.format("%.1f Hz", value), value -> config.shakeFrequency = value);
		slider(columns, "Max offset", 0.0, 30.0, config.shakeMaxOffset, 0.5,
				value -> String.format("%.1f px", value), value -> config.shakeMaxOffset = value);
	}

	private void buildGeneral() {
		Config config = LyriRfy.config();
		Columns columns = new Columns();
		toggle(columns, "LyriRfy enabled", config.enabled, value -> config.enabled = value);
		toggle(columns, "Show when paused", config.showWhenPaused, value -> config.showWhenPaused = value);
		toggle(columns, "Fetch lyrics (LRCLIB)", config.fetchLyrics, value -> config.fetchLyrics = value);
		slider(columns, "Spotify poll rate", 1000, 10000, config.pollIntervalMs, 250,
				value -> Math.round(value) + " ms", value -> config.pollIntervalMs = (int) Math.round(value));
		action(columns, "Reset everything", () -> {
			Config defaults = new Config();
			defaults.clientId = config.clientId;
			defaults.clientSecret = config.clientSecret;
			defaults.redirectUri = config.redirectUri;
			config.copyFrom(defaults);
			tab = 0;
			rebuildWidgets();
		});
		action(columns, "Open config folder", () -> Util.getPlatform().openPath(LyriRfy.configDir()));
		wrapped.add(new Wrapped("Lyrics come from LRCLIB (lrclib.net), a free and open lyric database that provides "
				+ "time synced lyrics. Spotify itself does not expose lyrics through its API.",
				SIDE, height - 30 - 28, width - SIDE * 2, 0xFF8A8A96));
	}

	// ================================================================== rendering

	@Override
	public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		graphics.fill(0, 0, width, 48, 0xA0080810);
		graphics.fill(0, 47, width, 48, 0xFF1ED760);
		graphics.fill(0, height - 30, width, height, 0xA0080810);
		graphics.fillGradient(SIDE, TOP - 4, width - SIDE, TOP - 3, 0x401ED760, 0x001ED760);

		if (tab >= 0 && tab < tabX.length) {
			graphics.fill(tabX[tab], tabY + 19, tabX[tab] + tabWidth[tab], tabY + 21, 0xFF1ED760);
		}
		graphics.centeredText(font, Component.literal("LyriRfy"), width / 2, 6, 0xFFFFFFFF);
		graphics.centeredText(font, Component.literal("Spotify lyrics in Minecraft"), width / 2, 16, 0xFF9A9AA6);

		super.extractRenderState(graphics, mouseX, mouseY, partialTick);

		for (Label label : labels) {
			graphics.text(font, label.text(), label.x(), label.y(), label.color(), label.shadow());
		}
		for (Wrapped text : wrapped) {
			graphics.textWithWordWrap(font, Component.literal(text.text()), text.x(), text.y(), text.width(), text.color());
		}
		if (tab == 0) {
			renderSpotifyStatus(graphics);
		}
	}

	private void renderSpotifyStatus(GuiGraphicsExtractor graphics) {
		SpotifyService spotify = LyriRfy.spotify();
		if (spotify == null) {
			return;
		}
		int color = switch (spotify.state()) {
			case CONNECTED -> 0xFF7BE495;
			case ERROR -> 0xFFFF7B7B;
			case CONNECTING -> 0xFFFFD166;
			default -> 0xFF9A9AA6;
		};
		graphics.textWithWordWrap(font, Component.literal(spotify.status()), SIDE, statusY, width - SIDE * 2, color);

		var playing = spotify.nowPlaying();
		if (playing != null) {
			String track = playing.title() + " — " + playing.artist();
			graphics.text(font, TextUtilHolder.truncate(font, track, width - SIDE * 2), SIDE, trackY, 0xFFFFFFFF, true);
			String detail = (playing.isPlaying() ? "Playing" : "Paused") + "  •  "
					+ dev.lyrirfy.render.TextUtil.formatTime(spotify.positionMs()) + " / "
					+ dev.lyrirfy.render.TextUtil.formatTime(playing.durationMs());
			graphics.text(font, detail, SIDE, trackY + 11, 0xFF9A9AA6);
		} else {
			graphics.text(font, "Nothing playing right now.", SIDE, trackY, 0xFF9A9AA6);
		}

		int hintOffset = spotifyHintOffset;
		if (!spotifyNotice.isBlank()) {
			int noticeColor = spotifyNotice.startsWith("Login accepted") ? 0xFF7BE495 : 0xFFFF9B9B;
			graphics.text(font, TextUtilHolder.truncate(font, spotifyNotice, width - SIDE * 2), SIDE, trackY + hintOffset, noticeColor);
		} else if (spotify.awaitingManualCode()) {
			graphics.text(font, "Open the login URL if no browser opened; it is also written to the game log.",
					SIDE, trackY + hintOffset, 0xFF9A9AA6);
		}
	}

	/** Keeps a reference to the render utility so the import is not removed accidentally. */
	private static final class TextUtilHolder {
		static String truncate(net.minecraft.client.gui.Font font, String text, int width) {
			return dev.lyrirfy.render.TextUtil.truncate(font, text, width);
		}
	}

	// ================================================================== resets

	private void resetCurrentTab() {
		Config config = LyriRfy.config();
		Config defaults = new Config();
		switch (tab) {
			case 1 -> {
				config.enable3d = defaults.enable3d;
				config.lyrics3dDistance = defaults.lyrics3dDistance;
				config.lyrics3dHeight = defaults.lyrics3dHeight;
				config.lyrics3dLateral = defaults.lyrics3dLateral;
				config.lyrics3dScale = defaults.lyrics3dScale;
				config.lyrics3dOpacity = defaults.lyrics3dOpacity;
				config.lyrics3dFixed = defaults.lyrics3dFixed;
				config.lyrics3dFixedFacePlayer = defaults.lyrics3dFixedFacePlayer;
				config.lyrics3dAvoidBlocks = defaults.lyrics3dAvoidBlocks;
				config.lyrics3dBobAmount = defaults.lyrics3dBobAmount;
				config.lyrics3dBobSpeed = defaults.lyrics3dBobSpeed;
				config.lyrics3dFadeInMs = defaults.lyrics3dFadeInMs;
				config.lyrics3dFadeOutMs = defaults.lyrics3dFadeOutMs;
				config.lyrics3dHoldMs = defaults.lyrics3dHoldMs;
				config.lyrics3dMaxWidth = defaults.lyrics3dMaxWidth;
				config.lyrics3dBackgroundAlpha = defaults.lyrics3dBackgroundAlpha;
				config.lyrics3dShowNext = defaults.lyrics3dShowNext;
			}
			case 2 -> {
				config.enableHud = defaults.enableHud;
				config.hudX = defaults.hudX;
				config.hudY = defaults.hudY;
				config.hudScale = defaults.hudScale;
				config.hudOpacity = defaults.hudOpacity;
				config.hudShowArtwork = defaults.hudShowArtwork;
				config.hudShowProgress = defaults.hudShowProgress;
				config.hudShowTime = defaults.hudShowTime;
				config.hudArtSize = defaults.hudArtSize;
				config.hudBackgroundAlpha = defaults.hudBackgroundAlpha;
			}
			case 3 -> {
				config.enable2dLyrics = defaults.enable2dLyrics;
				config.lyrics2dX = defaults.lyrics2dX;
				config.lyrics2dY = defaults.lyrics2dY;
				config.lyrics2dScale = defaults.lyrics2dScale;
				config.lyrics2dOpacity = defaults.lyrics2dOpacity;
				config.lyrics2dMaxWidth = defaults.lyrics2dMaxWidth;
				config.lyrics2dBackground = defaults.lyrics2dBackground;
				config.lyrics2dBackgroundAlpha = defaults.lyrics2dBackgroundAlpha;
				config.lyrics2dShowNext = defaults.lyrics2dShowNext;
				config.lyrics2dFadeInMs = defaults.lyrics2dFadeInMs;
				config.lyrics2dFadeOutMs = defaults.lyrics2dFadeOutMs;
				config.lyrics2dHoldMs = defaults.lyrics2dHoldMs;
			}
			case 4 -> {
				config.shakeEnabled = defaults.shakeEnabled;
				config.shakeStrength = defaults.shakeStrength;
				config.shakeJumpSensitivity = defaults.shakeJumpSensitivity;
				config.shakeCameraSensitivity = defaults.shakeCameraSensitivity;
				config.shakeDecay = defaults.shakeDecay;
				config.shakeFrequency = defaults.shakeFrequency;
				config.shakeMaxOffset = defaults.shakeMaxOffset;
				config.shakeAffectsHud = defaults.shakeAffectsHud;
				config.shakeAffects2d = defaults.shakeAffects2d;
			}
			case 5 -> {
				config.enabled = defaults.enabled;
				config.showWhenPaused = defaults.showWhenPaused;
				config.fetchLyrics = defaults.fetchLyrics;
				config.pollIntervalMs = defaults.pollIntervalMs;
			}
			case 0 -> config.redirectUri = Config.DEFAULT_REDIRECT_URI;
			default -> {
				// Nothing else to reset here.
			}
		}
		LyriRfy.markDirty();
	}
}
