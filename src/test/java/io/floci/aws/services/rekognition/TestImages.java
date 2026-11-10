package io.floci.aws.services.rekognition;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Base64;

/** Images for the Rekognition tests, built in memory. */
final class TestImages {

    /** A 100 x 100 WebP, the file the Rekognition probe sent, which Rekognition rejects. */
    static final byte[] WEBP = Base64.getDecoder().decode(
            "UklGRloAAABXRUJQVlA4IE4AAADQBQCdASpkAGQAPm02mUmkIyKhIKgAgA2JaW7hc+lwAAE97iLywDXU3uIvLANdTe4i8sA11N6IAP79TN//tTBJGUv//dBlnDdIAAAAAAA=");

    private TestImages() {
    }

    static byte[] jpeg(int width, int height) {
        return encode(width, height, "jpg");
    }

    static byte[] png(int width, int height) {
        return encode(width, height, "png");
    }

    static byte[] gif(int width, int height) {
        return encode(width, height, "gif");
    }

    /** Where the scan of a JPEG starts: the first byte after its start-of-scan header. */
    static int jpegScanStart(byte[] jpeg) {
        int header = jpegScanHeader(jpeg);
        return header + (((jpeg[header] & 0xFF) << 8) | (jpeg[header + 1] & 0xFF));
    }

    /** Where a JPEG's start-of-scan header starts: its two-byte length, then its component count. */
    static int jpegScanHeader(byte[] jpeg) {
        return jpegSegment(jpeg, 0xDA);
    }

    /** Where a JPEG's first segment with this marker starts: the index of its two-byte length. */
    static int jpegSegment(byte[] jpeg, int wanted) {
        int i = 2;
        while (i < jpeg.length) {
            while ((jpeg[i] & 0xFF) == 0xFF) {
                i++;
            }
            int marker = jpeg[i] & 0xFF;
            i++;
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                continue;
            }
            int length = ((jpeg[i] & 0xFF) << 8) | (jpeg[i + 1] & 0xFF);
            if (marker == wanted) {
                return i;
            }
            i += length;
        }
        throw new IllegalArgumentException("No segment with marker " + Integer.toHexString(wanted));
    }

    /** The first {@code length} bytes of {@code image}, as a cut-off upload would leave it. */
    static byte[] truncated(byte[] image, int length) {
        return Arrays.copyOf(image, length);
    }

    private static byte[] encode(int width, int height, String format) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                image.setRGB(x, y, 0x78A0C8);
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
