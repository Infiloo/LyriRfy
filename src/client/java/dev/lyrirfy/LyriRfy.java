package dev.lyrirfy;

import dev.lyrirfy.config.Config;
import dev.lyrirfy.render.HudOverlay;
import dev.lyrirfy.render.ShakeState;
import dev.lyrirfy.render.WorldLyricsRenderer;
import dev.lyrirfy.spotify.SpotifyService;
import dev.lyrirfy.ui.ConfigScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

public final class LyriRfy implements ClientModInitializer {
	public static final String MOD_ID = "lyrirfy";
	public static final String MOD_VERSION = "1.0.0";
	public static final Logger LOGGER = LoggerFactory.getLogger("LyriRfy");

	private static Config config;
	private static SpotifyService spotify;
	private static ShakeState shake;
	private static KeyMapping openMenuKey;
	private static Path configPath;
	private static long dirtySince = -1L;
	private static long lastSaveAt;

	public static Config config() {
		return config;
	}

	public static SpotifyService spotify() {
		return spotify;
	}

	public static ShakeState shake() {
		return shake;
	}

	public static Path configDir() {
		return FabricLoader.getInstance().getConfigDir();
	}

	/** Marks the config dirty; it is flushed to disk shortly after the last edit. */
	public static void markDirty() {
		dirtySince = System.currentTimeMillis();
	}

	public static void saveConfigNow() {
		dirtySince = -1L;
		lastSaveAt = System.currentTimeMillis();
		config.save(configPath);
	}

	@Override
	public void onInitializeClient() {
		configPath = configDir().resolve("lyrirfy.json");
		config = Config.load(configPath);
		spotify = new SpotifyService(config, configDir());
		shake = new ShakeState();

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, MOD_ID));
		openMenuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.lyrirfy.open_menu", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			boolean open = false;
			while (openMenuKey.consumeClick()) {
				open = true;
			}
			if (open && client.gui.screen() == null) {
				client.gui.setScreen(new ConfigScreen());
			}
			spotify.tick();
			shake.tick(client);
			flushConfigIfNeeded();
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			if (config != null) {
				saveConfigNow();
			}
		});

		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(MOD_ID, "overlay"), new HudOverlay());
		WorldLyricsRenderer.register();

		LOGGER.info("LyriRfy {} loaded. Press F9 in game to open the Spotify + graphics menu.", MOD_VERSION);
	}

	private static void flushConfigIfNeeded() {
		if (dirtySince < 0L) {
			return;
		}
		long now = System.currentTimeMillis();
		if (now - dirtySince < 500L || now - lastSaveAt < 500L) {
			return;
		}
		saveConfigNow();
	}
}
