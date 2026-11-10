package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DigestingInputStreamTest {

    @Test
    void aBodyOfExactlyItsLimitIsReadWhole() throws IOException {
        DigestingInputStream body = new DigestingInputStream(
                new ByteArrayInputStream("0123456789".getBytes(StandardCharsets.UTF_8)), List.of(), 10);

        assertArrayEquals("0123456789".getBytes(StandardCharsets.UTF_8), body.readAllBytes());
        assertEquals(10, body.size());
    }

    @Test
    void aBodyThatRunsPastItsLimitIsEntityTooLarge() {
        DigestingInputStream body = new DigestingInputStream(
                new ByteArrayInputStream("0123456789A".getBytes(StandardCharsets.UTF_8)), List.of(), 10);

        AwsException error = assertThrows(AwsException.class, body::readAllBytes);

        assertEquals("EntityTooLarge", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("Your proposed upload exceeds the maximum allowed size", error.getMessage());
        // Only the bytes read so far are known, so only the limit is named.
        assertEquals(Map.of("MaxSizeAllowed", 10L), error.getExtendedData());
    }

    @Test
    void aSingleByteReadPastTheLimitIsEntityTooLarge() throws IOException {
        DigestingInputStream body = new DigestingInputStream(
                new ByteArrayInputStream("ab".getBytes(StandardCharsets.UTF_8)), List.of(), 1);

        assertEquals('a', body.read());
        AwsException error = assertThrows(AwsException.class, body::read);

        assertEquals("EntityTooLarge", error.getErrorCode());
        assertEquals(1, body.size());
    }
}
