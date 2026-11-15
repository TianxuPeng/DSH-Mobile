package com.dshmobile.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNull;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.Test;

public class QrDecoderTest {

    private static final int SIZE = 400;

    @Test
    public void decodesUprightQr() throws WriterException {
        String content = "https://example.com/path?q=1";
        byte[] luminance = qrLuminance(content);
        assertEquals(content, new QrDecoder().decode(luminance, SIZE, SIZE, 0));
    }

    @Test
    public void decodesQrAfterRotationCorrection() throws WriterException {
        String content = "https://example.com/rotated";
        byte[] upright = qrLuminance(content);

        assertDecodesAfterRotation(content, upright, 90);
        assertDecodesAfterRotation(content, upright, 180);
        assertDecodesAfterRotation(content, upright, 270);
    }

    @Test
    public void returnsNullForBlankFrame() {
        byte[] blank = new byte[SIZE * SIZE];
        for (int i = 0; i < blank.length; i++) {
            blank[i] = (byte) 255;
        }
        assertNull(new QrDecoder().decode(blank, SIZE, SIZE, 0));
    }

    @Test
    public void rotatesThreeByTwoClockwise() {
        byte[] source = new byte[]{
                1, 2, 3,
                4, 5, 6
        };
        byte[] target = new byte[6];
        QrDecoder.rotate(source, 3, 2, 90, target);
        assertArrayEquals(new byte[]{
                4, 1,
                5, 2,
                6, 3
        }, target);
    }

    private static void assertDecodesAfterRotation(
            String content, byte[] upright, int correctionDegrees) {
        // The camera buffer is the upright scene anti-rotated by the correction
        // that CameraX reports. The decoder then applies that correction.
        byte[] raw = upright;
        int width = SIZE;
        int height = SIZE;
        byte[] rotated = new byte[SIZE * SIZE];
        int inverse = (360 - correctionDegrees) % 360;
        QrDecoder.rotate(upright, width, height, inverse, rotated);
        raw = rotated;

        assertEquals(content, new QrDecoder().decode(raw, width, height, correctionDegrees));
    }

    private static byte[] qrLuminance(String content) throws WriterException {
        BitMatrix matrix = new QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, SIZE, SIZE);
        byte[] luminance = new byte[SIZE * SIZE];
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                luminance[y * SIZE + x] = (byte) (matrix.get(x, y) ? 0 : 255);
            }
        }
        return luminance;
    }
}
