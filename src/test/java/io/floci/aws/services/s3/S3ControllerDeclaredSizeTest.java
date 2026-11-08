package io.floci.aws.services.s3;

import io.floci.aws.core.common.AwsException;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3ControllerDeclaredSizeTest {

    private static final long LIMIT = S3Service.MAX_PUT_OBJECT_SIZE;
    private static final String STREAMING = "STREAMING-UNSIGNED-PAYLOAD-TRAILER";

    private static HttpHeaders headers(String contentLength, String decodedContentLength) {
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getHeaderString("Content-Length")).thenReturn(contentLength);
        when(headers.getHeaderString("x-amz-decoded-content-length")).thenReturn(decodedContentLength);
        return headers;
    }

    @Test
    void aBodyOverTheLimitIsEntityTooLargeBeforeItIsRead() {
        AwsException error = assertThrows(AwsException.class, () -> S3Controller.requireDeclaredSizeWithin(
                headers(String.valueOf(LIMIT + 1), null), null, "UNSIGNED-PAYLOAD", LIMIT));

        assertEquals("EntityTooLarge", error.getErrorCode());
        assertEquals(400, error.getHttpStatus());
        assertEquals("Your proposed upload exceeds the maximum allowed size", error.getMessage());
        assertEquals(Map.of("ProposedSize", LIMIT + 1, "MaxSizeAllowed", LIMIT), error.getExtendedData());
    }

    @Test
    void aBodyOfExactlyTheLimitIsAccepted() {
        assertDoesNotThrow(() -> S3Controller.requireDeclaredSizeWithin(
                headers(String.valueOf(LIMIT), null), null, "UNSIGNED-PAYLOAD", LIMIT));
    }

    @Test
    void anAwsChunkedBodyIsMeasuredByItsDecodedLengthNotItsFraming() {
        // The framing takes the Content-Length past the limit while the data is exactly the limit.
        assertDoesNotThrow(() -> S3Controller.requireDeclaredSizeWithin(
                headers(String.valueOf(LIMIT + 4096), String.valueOf(LIMIT)), "aws-chunked", STREAMING, LIMIT));
        assertDoesNotThrow(() -> S3Controller.requireDeclaredSizeWithin(
                headers(String.valueOf(LIMIT + 4096), String.valueOf(LIMIT)), null, STREAMING, LIMIT));

        assertThrows(AwsException.class, () -> S3Controller.requireDeclaredSizeWithin(
                headers("100", String.valueOf(LIMIT + 1)), "aws-chunked", STREAMING, LIMIT));
        assertThrows(AwsException.class, () -> S3Controller.requireDeclaredSizeWithin(
                headers("100", String.valueOf(LIMIT + 1)), "gzip, aws-chunked", "UNSIGNED-PAYLOAD", LIMIT));
    }

    @Test
    void aBodyThatDeclaresNoUsableSizeIsLeftToTheLimitAsItIsRead() {
        assertDoesNotThrow(() -> S3Controller.requireDeclaredSizeWithin(
                headers(null, null), null, "UNSIGNED-PAYLOAD", LIMIT));
        assertDoesNotThrow(() -> S3Controller.requireDeclaredSizeWithin(
                headers(String.valueOf(LIMIT + 1), null), "aws-chunked", STREAMING, LIMIT));
        assertDoesNotThrow(() -> S3Controller.requireDeclaredSizeWithin(
                headers("100", "not-a-size"), "aws-chunked", STREAMING, LIMIT));
    }
}
