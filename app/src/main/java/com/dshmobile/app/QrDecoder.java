package com.dshmobile.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

/**
 * Small, single-threaded QR-only decoder around ZXing. It owns the rotation
 * scratch buffer so the camera thread does not allocate on every frame.
 */
final class QrDecoder {

    private final MultiFormatReader reader = new MultiFormatReader();
    private byte[] rotatedBuffer;

    QrDecoder() {
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, EnumSet.of(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        reader.setHints(hints);
    }

    /**
     * @param luminance tightly packed grayscale data
     * @param rotation  clockwise rotation needed to make the buffer upright
     * @return decoded text, or {@code null} if this frame has no readable QR
     */
    String decode(byte[] luminance, int width, int height, int rotation) {
        if (luminance == null || width <= 0 || height <= 0
                || luminance.length < width * height) {
            return null;
        }

        int normalized = ((rotation % 360) + 360) % 360;
        int sourceWidth = width;
        int sourceHeight = height;
        byte[] sourceData = luminance;

        if (normalized == 90 || normalized == 270) {
            ensureRotatedCapacity(luminance.length);
            rotate(luminance, width, height, normalized, rotatedBuffer);
            sourceWidth = height;
            sourceHeight = width;
            sourceData = rotatedBuffer;
        } else if (normalized == 180) {
            ensureRotatedCapacity(luminance.length);
            rotate(luminance, width, height, 180, rotatedBuffer);
            sourceData = rotatedBuffer;
        }

        try {
            PlanarYUVLuminanceSource source = new PlanarYUVLuminanceSource(
                    sourceData,
                    sourceWidth,
                    sourceHeight,
                    0,
                    0,
                    sourceWidth,
                    sourceHeight,
                    false);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = reader.decodeWithState(bitmap);
            return result == null ? null : result.getText();
        } catch (ReaderException e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private void ensureRotatedCapacity(int length) {
        if (rotatedBuffer == null || rotatedBuffer.length < length) {
            rotatedBuffer = new byte[length];
        }
    }

    /**
     * Rotates a tightly packed W x H luminance plane clockwise.
     */
    static void rotate(byte[] source, int width, int height, int rotation, byte[] target) {
        if (rotation == 90) {
            int outWidth = height;
            for (int y = 0; y < height; y++) {
                int srcRow = y * width;
                int destCol = height - 1 - y;
                for (int x = 0; x < width; x++) {
                    target[x * outWidth + destCol] = source[srcRow + x];
                }
            }
        } else if (rotation == 180) {
            for (int y = 0; y < height; y++) {
                int srcRow = y * width;
                int destRow = (height - 1 - y) * width;
                for (int x = 0; x < width; x++) {
                    target[destRow + (width - 1 - x)] = source[srcRow + x];
                }
            }
        } else if (rotation == 270) {
            for (int y = 0; y < height; y++) {
                int srcRow = y * width;
                for (int x = 0; x < width; x++) {
                    target[(width - 1 - x) * height + y] = source[srcRow + x];
                }
            }
        }
    }
}
