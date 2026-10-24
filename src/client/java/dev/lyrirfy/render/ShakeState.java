package dev.lyrirfy.render;

import dev.lyrirfy.LyriRfy;
import dev.lyrirfy.config.Config;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;

/**
 * Turns player movement into a decaying camera shake. Only the 2D elements shake,
 * the world stays perfectly stable.
 */
public final class ShakeState {
	private double energy;
	private double smoothedEnergy;
	private long lastTickMs = System.currentTimeMillis();
	private float lastYaw;
	private float lastPitch;
	private boolean hasPreviousRotation;
	private double lastVerticalVelocity;
	private boolean wasOnGround = true;

	public void tick(Minecraft minecraft) {
		Config config = LyriRfy.config();
		long now = System.currentTimeMillis();
		double deltaSeconds = Math.min(0.25, Math.max(0.0, (now - lastTickMs) / 1000.0));
		lastTickMs = now;

		if (!config.shakeEnabled) {
			energy = 0;
			smoothedEnergy = 0;
			return;
		}

		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.level == null) {
			energy = 0;
			smoothedEnergy = 0;
			return;
		}

		double impulse = 0.0;

		double verticalVelocity = player.getDeltaMovement().y;
		if (!player.onGround() || Math.abs(verticalVelocity) > 0.001) {
			impulse += Math.abs(verticalVelocity) * 1.15;
		}
		// Landing thump: the downward speed disappears in a single tick.
		if (!wasOnGround && player.onGround()) {
			impulse += Math.min(2.5, Math.abs(lastVerticalVelocity)) * 1.7;
		}
		wasOnGround = player.onGround();
		lastVerticalVelocity = verticalVelocity;

		if (hasPreviousRotation) {
			double yawDelta = Math.abs(Mth.wrapDegrees(player.getYRot() - lastYaw));
			double pitchDelta = Math.abs(Mth.wrapDegrees(player.getXRot() - lastPitch));
			double cameraSpeed = (yawDelta + pitchDelta) / 16.0;
			impulse += cameraSpeed * config.shakeCameraSensitivity * 0.9;
		}
		lastYaw = player.getYRot();
		lastPitch = player.getXRot();
		hasPreviousRotation = true;

		impulse *= config.shakeJumpSensitivity;
		energy = Math.min(3.0, energy + impulse * 0.55 * config.shakeStrength);
		energy *= Math.exp(-config.shakeDecay * deltaSeconds);
		if (energy < 0.0005) {
			energy = 0;
		}

		double smoothing = Math.min(1.0, deltaSeconds * 22.0);
		smoothedEnergy += (energy - smoothedEnergy) * smoothing;
	}

	public float offsetX() {
		Config config = LyriRfy.config();
		if (!config.shakeEnabled || smoothedEnergy <= 0.0) {
			return 0.0F;
		}
		double time = System.currentTimeMillis() / 1000.0;
		double wave = Math.sin(time * config.shakeFrequency) * 0.58
				+ Math.sin(time * config.shakeFrequency * 1.73 + 1.7) * 0.42;
		return (float) (wave * smoothedEnergy * config.shakeMaxOffset * 0.55);
	}

	public float offsetY() {
		Config config = LyriRfy.config();
		if (!config.shakeEnabled || smoothedEnergy <= 0.0) {
			return 0.0F;
		}
		double time = System.currentTimeMillis() / 1000.0;
		double wave = Math.sin(time * config.shakeFrequency * 1.13 + 0.9) * 0.58
				+ Math.cos(time * config.shakeFrequency * 1.91 + 0.35) * 0.42;
		return (float) (wave * smoothedEnergy * config.shakeMaxOffset * 0.55);
	}

	public double energy() {
		return smoothedEnergy;
	}
}
