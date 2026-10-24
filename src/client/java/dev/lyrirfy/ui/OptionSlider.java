package dev.lyrirfy.ui;

import dev.lyrirfy.LyriRfy;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/** A slider whose label always shows the live, formatted value. */
public class OptionSlider extends AbstractSliderButton {
	private final String label;
	private final double min;
	private final double max;
	private final double step;
	private final DoubleFunction<String> formatter;
	private final DoubleConsumer setter;
	private boolean ready;

	public OptionSlider(int x, int y, int width, int height, String label, double min, double max, double value,
			double step, DoubleFunction<String> formatter, DoubleConsumer setter) {
		super(x, y, width, height, Component.empty(), toFraction(value, min, max));
		this.label = label;
		this.min = min;
		this.max = max;
		this.step = step;
		this.formatter = formatter;
		this.setter = setter;
		this.ready = true;
		updateMessage();
	}

	private static double toFraction(double value, double min, double max) {
		if (max <= min) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, (value - min) / (max - min)));
	}

	public double actualValue() {
		double raw = min + (max - min) * this.value;
		if (step > 0.0) {
			raw = Math.round(raw / step) * step;
		}
		return Math.max(min, Math.min(max, raw));
	}

	@Override
	protected void updateMessage() {
		if (!ready) {
			setMessage(Component.empty());
			return;
		}
		setMessage(Component.literal(label + ": " + formatter.apply(actualValue())));
	}

	@Override
	protected void applyValue() {
		if (!ready) {
			return;
		}
		setter.accept(actualValue());
		LyriRfy.markDirty();
		updateMessage();
	}
}
