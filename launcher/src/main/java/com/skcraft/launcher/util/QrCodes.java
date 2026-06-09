package com.skcraft.launcher.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumMap;
import java.util.Map;

/**
 * Local QR code rendering for the launcher UI.
 */
public final class QrCodes {

	private QrCodes() {
	}

	public static BufferedImage generate(String contents, int size) {
		try {
			Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
			hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
			hints.put(EncodeHintType.MARGIN, 0);

			BitMatrix matrix = new QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, size, size, hints);
			return toImage(matrix);
		} catch (WriterException e) {
			return null;
		}
	}

	private static BufferedImage toImage(BitMatrix matrix) {
		int width = matrix.getWidth();
		int height = matrix.getHeight();
		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < height; y++) {
			for (int x = 0; x < width; x++) {
				image.setRGB(x, y, matrix.get(x, y) ? Color.BLACK.getRGB() : Color.WHITE.getRGB());
			}
		}
		return image;
	}
}
