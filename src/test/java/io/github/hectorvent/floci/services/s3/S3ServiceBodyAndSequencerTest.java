package io.github.hectorvent.floci.services.s3;

import io.github.hectorvent.floci.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3ServiceBodyAndSequencerTest {

    @Test
    void toSequencerMatchesFormattedValue() {
        long[] values = {0, 1, 15, 16, 255, 0xABCDEF, System.currentTimeMillis() * 1000, Long.MAX_VALUE};
        for (long value : values) {
            assertEquals(String.format(Locale.ROOT, "%018X", value), S3Service.toSequencer(value));
        }
    }

    @Test
    void readWithinReadsBodyOfDeclaredSize() {
        byte[] body = bytes(1000);
        assertArrayEquals(body, S3Service.readWithin(new ByteArrayInputStream(body), 5000, 1000, n -> { }));
    }

    @Test
    void readWithinAcceptsShorterBodyThanDeclared() {
        byte[] body = bytes(10);
        assertArrayEquals(body, S3Service.readWithin(new ByteArrayInputStream(body), 5000, 1000, n -> { }));
    }

    @Test
    void readWithinAcceptsLongerBodyThanDeclared() {
        byte[] body = bytes(30000);
        assertArrayEquals(body, S3Service.readWithin(new ByteArrayInputStream(body), 50000, 100, n -> { }));
    }

    @Test
    void readWithinReadsShortBodyWhenDeclaredSizeIsAboveExactReadLimit() {
        byte[] body = bytes(10);
        long declared = 65L * 1024 * 1024;
        assertArrayEquals(body, S3Service.readWithin(new ByteArrayInputStream(body), Long.MAX_VALUE / 2, declared,
                n -> { }));
    }

    @Test
    void readWithinReadsEmptyBody() {
        assertEquals(0, S3Service.readWithin(new ByteArrayInputStream(new byte[0]), 5000, 0, n -> { }).length);
        assertEquals(1, S3Service.readWithin(new ByteArrayInputStream(bytes(1)), 5000, 0, n -> { }).length);
    }

    @Test
    void readWithinReadsBodyWithoutDeclaredSize() {
        byte[] body = bytes(20000);
        assertArrayEquals(body, S3Service.readWithin(new ByteArrayInputStream(body), 50000, -1, n -> { }));
        assertArrayEquals(body, S3Service.readWithin(new ByteArrayInputStream(body), 50000, n -> { }));
    }

    @Test
    void readWithinRejectsBodyPastMaxSize() {
        long[] reported = {-1};
        assertThrows(AwsException.class, () -> S3Service.readWithin(
                new ByteArrayInputStream(bytes(200)), 100, 50, n -> reported[0] = n));
        assertEquals(200, reported[0]);
    }

    @Test
    void readWithinRejectsDeclaredSizeAboveMaxSizeWhenBodyIsLarger() {
        assertThrows(AwsException.class, () -> S3Service.readWithin(
                new ByteArrayInputStream(bytes(200)), 100, 150, n -> { }));
    }

    private static byte[] bytes(int length) {
        byte[] data = new byte[length];
        for (int index = 0; index < length; index++) {
            data[index] = (byte) (index * 31);
        }
        return data;
    }
}
