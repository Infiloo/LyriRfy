package dev.lyrirfy.render;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.lyrirfy.LyriRfy;
import dev.lyrirfy.config.Config;
import dev.lyrirfy.lyrics.LyricsDoc;
import dev.lyrirfy.spotify.SpotifyService;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.ARGB;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;

/**
 * Renders the current lyric line as billboarded 3D text floating in front of the player.
 * The fixed mode pins a line to the world position where it first appeared.
 */
public final class WorldLyricsRenderer {
	private static final int FULL_BRIGHT = 0xF000F0;
	private static final float BASE_TEXT_SCALE = 0.025F;

	private static String anchoredKey = "";
	private static final Vec3 ANCHOR_POSITION = Vec3.ZERO;
	private static Vec3 anchor = Vec3.ZERO;
	private static final Quaternionf anchorRotation = new Quaternionf();
	private static Vec3 anchorPlayerPos = Vec3.ZERO;

	private WorldLyricsRenderer() {
	}

	public static void register() {
		LevelRenderEvents.COLLECT_SUBMITS.register(WorldLyricsRenderer::collect);
	}

	private static void collect(LevelRenderContext context) {
		Config config = LyriRfy.config();
		if (!config.enabled || !config.enable3d) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || minecraft.gui.hud.isHidden() || minecraft.gui.screen() != null) {
			return;
		}
		SpotifyService spotify = LyriRfy.spotify();
		if (!spotify.isVisible(config)) {
			return;
		}
		LyricsDoc doc = spotify.lyrics();
		if (doc == null || doc.isEmpty()) {
			return;
		}

		long position = spotify.positionMs();
		TextUtil.Frame frame = TextUtil.frame(doc, position, config.lyrics3dFadeInMs, config.lyrics3dFadeOutMs,
				config.lyrics3dHoldMs, config.lyrics3dOpacity, config.lyrics3dShowNext);
		if (frame == null) {
			return;
		}

		CameraRenderState camera = context.levelState().cameraRenderState;
		if (!camera.initialized) {
			return;
		}
		Font font = minecraft.font;

		Vector3f forward = new Vector3f(0.0F, 0.0F, -1.0F).rotate(camera.orientation);
		Vector3f up = new Vector3f(0.0F, 1.0F, 0.0F).rotate(camera.orientation);
		Vector3f left = new Vector3f(-1.0F, 0.0F, 0.0F).rotate(camera.orientation);

		String lineKey = spotify.nowPlaying() == null ? "?" : spotify.nowPlaying().trackKey() + "#" + frame.index();
		boolean fixed = config.lyrics3dFixed;
		Vec3 base;
		Quaternionf rotation;

		if (fixed) {
			if (!lineKey.equals(anchoredKey)) {
				anchoredKey = lineKey;
				anchor = computePosition(camera, config, forward, up, left, minecraft);
				anchorRotation.set(camera.orientation);
				anchorPlayerPos = camera.pos;
			}
			base = anchor;
			rotation = config.lyrics3dFixedFacePlayer ? camera.orientation : anchorRotation;
			if (camera.pos.distanceTo(anchorPlayerPos) > 64.0) {
				anchoredKey = "";
			}
		} else {
			base = computePosition(camera, config, forward, up, left, minecraft);
			double time = System.currentTimeMillis() / 1000.0 * config.lyrics3dBobSpeed;
			double bob = Math.sin(time) * config.lyrics3dBobAmount;
			double sway = Math.sin(time * 0.61 + 1.3) * config.lyrics3dBobAmount * 0.55;
			base = base.add(up.x * bob, up.y * bob, up.z * bob).add(left.x * sway, left.y * sway, left.z * sway);
			rotation = camera.orientation;
		}

		List<FormattedCharSequence> lines = TextUtil.wrap(font, frame.text(), config.lyrics3dMaxWidth);
		int lineHeight = 10;
		int blockHeight = lines.size() * lineHeight;
		int alpha = TextUtil.alphaInt(frame.alpha());
		int textColor = ARGB.color(alpha, 255, 255, 255);
		int backgroundColor = ARGB.color((config.lyrics3dBackgroundAlpha * alpha) / 255, 0, 0, 0);
		float scale = BASE_TEXT_SCALE * (float) config.lyrics3dScale * (0.86F + 0.14F * frame.entrance());

		PoseStack pose = context.poseStack();
		pose.pushPose();
		pose.translate(base.x - camera.pos.x, base.y - camera.pos.y, base.z - camera.pos.z);
		pose.mulPose(rotation);
		pose.scale(scale, -scale, scale);

		SubmitNodeCollector collector = context.submitNodeCollector();
		for (int i = 0; i < lines.size(); i++) {
			FormattedCharSequence sequence = lines.get(i);
			float x = -font.width(sequence) / 2.0F;
			float y = -blockHeight / 2.0F + i * lineHeight + frame.slide() * 2.0F;
			collector.submitText(pose, x, y, sequence, true, Font.DisplayMode.NORMAL, FULL_BRIGHT, textColor,
					backgroundColor, 0x000000);
		}

		if (frame.nextText() != null) {
			List<FormattedCharSequence> nextLines = TextUtil.wrap(font, frame.nextText(), config.lyrics3dMaxWidth);
			int nextAlpha = (int) (alpha * 0.42F);
			int nextColor = ARGB.color(nextAlpha, 255, 255, 255);
			float nextY = blockHeight / 2.0F + 3.0F;
			for (int i = 0; i < nextLines.size() && i < 2; i++) {
				FormattedCharSequence sequence = nextLines.get(i);
				collector.submitText(pose, -font.width(sequence) / 2.0F, nextY + i * lineHeight, sequence, false,
						Font.DisplayMode.NORMAL, FULL_BRIGHT, nextColor, 0, 0x000000);
			}
		}
		pose.popPose();
	}

	private static Vec3 computePosition(CameraRenderState camera, Config config, Vector3f forward, Vector3f up,
			Vector3f left, Minecraft minecraft) {
		Vec3 base = camera.pos
				.add(forward.x * config.lyrics3dDistance, forward.y * config.lyrics3dDistance, forward.z * config.lyrics3dDistance)
				.add(up.x * config.lyrics3dHeight, up.y * config.lyrics3dHeight, up.z * config.lyrics3dHeight)
				.add(left.x * config.lyrics3dLateral, left.y * config.lyrics3dLateral, left.z * config.lyrics3dLateral);

		if (config.lyrics3dAvoidBlocks && minecraft.level != null) {
			Entity cameraEntity = minecraft.getCameraEntity();
			HitResult hit = minecraft.level.clip(new ClipContext(camera.pos, base, ClipContext.Block.VISUAL,
					ClipContext.Fluid.NONE, cameraEntity));
			if (hit.getType() != HitResult.Type.MISS) {
				Vec3 direction = base.subtract(camera.pos);
				double length = direction.length();
				if (length > 0.001) {
					direction = direction.scale(1.0 / length);
					base = hit.getLocation().subtract(direction.scale(0.45));
				}
			}
		}
		return base;
	}
}
