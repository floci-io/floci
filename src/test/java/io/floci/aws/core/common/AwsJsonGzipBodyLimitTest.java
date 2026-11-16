package io.floci.aws.core.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.floci.aws.testing.GzipFixtures.gzip;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AwsJsonGzipBodyLimitTest {

    @Test
    void cloudWatchAcceptsItsExactLimit() throws IOException {
        byte[] body = new byte[5 * 1024 * 1024];
        assertArrayEquals(body, AwsJsonCborController.decodeBody(
                gzip(body), body.length, "RequestEntityTooLargeException"));
    }

    @Test
    void cloudWatchRejectsOneByteOverItsLimit() throws IOException {
        byte[] body = new byte[5 * 1024 * 1024 + 1];
        byte[] compressed = gzip(body);
        AwsException error = assertThrows(AwsException.class, () ->
                AwsJsonCborController.decodeBody(compressed, body.length - 1, "RequestEntityTooLargeException"));
        assertEquals("RequestEntityTooLargeException", error.getErrorCode());
        assertEquals(413, error.getHttpStatus());
    }

    @Test
    void cborRetainsItsLargerLimitAndError() throws IOException {
        byte[] body = new byte[10 * 1024 * 1024];
        assertArrayEquals(body, AwsJsonCborController.decodeBody(gzip(body)));
        byte[] compressed = gzip(new byte[body.length + 1]);
        AwsException error = assertThrows(AwsException.class, () -> AwsJsonCborController.decodeBody(compressed));
        assertEquals("PayloadTooLargeException", error.getErrorCode());
        assertEquals(413, error.getHttpStatus());
    }
}
