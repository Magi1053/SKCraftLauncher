/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.swing;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.function.Consumer;

/**
 * Theme-aware painted icons for Swing UI.
 */
public final class SwingIcons {

	private SwingIcons() {
	}

	public static Icon copy(int size) {
		return paint(size, g -> {
			Color stroke = SwingHelper.uiColor("Label.foreground", Color.DARK_GRAY);
			Color fill = SwingHelper.uiColor("TextField.background", Color.WHITE);
			int pad = Math.max(2, size / 9);
			int w = size - pad * 2 - 3;
			int h = size - pad * 2 - 3;
			g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.setColor(stroke);
			g.drawRoundRect(pad + 3, pad, w, h, 3, 3);
			g.setColor(fill);
			g.fillRoundRect(pad, pad + 3, w, h, 3, 3);
			g.setColor(stroke);
			g.drawRoundRect(pad, pad + 3, w, h, 3, 3);
		});
	}

	public static Icon check(int size) {
		return paint(size, g -> {
			g.setColor(SwingHelper.uiColor("Actions.Green", new Color(34, 139, 34)));
			g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			Path2D path = new Path2D.Float();
			path.moveTo(size * 0.2, size * 0.52);
			path.lineTo(size * 0.42, size * 0.72);
			path.lineTo(size * 0.8, size * 0.32);
			g.draw(path);
		});
	}

	public static Icon plus(int size) {
		return paint(size, g -> {
			g.setColor(SwingHelper.uiColor("Button.foreground", new Color(55, 55, 55)));
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int c = size / 2;
			int pad = size / 5;
			g.drawLine(pad, c, size - pad, c);
			g.drawLine(c, pad, c, size - pad);
		});
	}

	public static Icon forget(int size, Color color) {
		return paint(size, g -> {
			g.setColor(color);
			g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			int pad = size / 5;
			g.drawLine(pad, pad, size - pad, size - pad);
			g.drawLine(size - pad, pad, pad, size - pad);
		});
	}

	public static Icon paint(int size, Consumer<Graphics2D> painter) {
		return new Icon() {
			@Override
			public void paintIcon(Component c, Graphics g, int x, int y) {
				Graphics2D g2 = (Graphics2D) g.create(x, y, size, size);
				try {
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
					painter.accept(g2);
				} finally {
					g2.dispose();
				}
			}

			@Override
			public int getIconWidth() {
				return size;
			}

			@Override
			public int getIconHeight() {
				return size;
			}
		};
	}
}
