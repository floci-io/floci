package io.github.hectorvent.floci.services.cloudfront;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** How the length of a viewer's request body is taken before it is forwarded to a custom origin. */
class CloudFrontViewerBodyTest {

    private static final byte[] DATA = "a viewer upload".getBytes(StandardCharsets.UTF_8);

    @Test
    void aChunkedBodyIsForwardedChunkedWhateverItsContentLength() throws IOException {
        CloudFrontServingController.ViewerBody body =
                CloudFrontServingController.viewerBody(new ByteArrayInputStream(DATA), "chunked", "3");

        assertEquals(-1, body.length());
        assertArrayEquals(DATA, readAll(body));
    }

    @Test
    void aBodyWithAContentLengthKeepsIt() throws IOException {
        CloudFrontServingController.ViewerBody body = CloudFrontServingController.viewerBody(
                new ByteArrayInputStream(DATA), null, Integer.toString(DATA.length));

        assertEquals(DATA.length, body.length());
        assertArrayEquals(DATA, readAll(body));
    }

    @Test
    void anInvalidContentLengthIsForwardedChunked() {
        assertEquals(-1, CloudFrontServingController.viewerBody(
                new ByteArrayInputStream(DATA), null, "not-a-length").length());
    }

    @Test
    void aRequestWithNeitherHeaderAndNoBodyIsEmpty() {
        assertEquals(0, CloudFrontServingController.viewerBody(InputStream.nullInputStream(), null, null).length());
        assertEquals(0, CloudFrontServingController.viewerBody(null, null, null).length());
    }

    @Test
    void aBodyWithNeitherHeaderAsHttp2AllowsIsForwardedChunkedAndWhole() throws IOException {
        CloudFrontServingController.ViewerBody body =
                CloudFrontServingController.viewerBody(new ByteArrayInputStream(DATA), null, null);

        assertEquals(-1, body.length());
        assertArrayEquals(DATA, readAll(body), "the byte read to look at the body was lost");
    }

    private static byte[] readAll(CloudFrontServingController.ViewerBody body) throws IOException {
        try (InputStream stream = body.stream()) {
            return stream.readAllBytes();
        }
    }
}
