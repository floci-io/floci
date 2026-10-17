package io.github.hectorvent.floci.services.lambdaweb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The page bodies must be byte for byte what AWS sends, so their recorded content lengths pin them. */
class LambdaWebErrorPagesTest {

    private static final String REQUEST_ID = "WLYFST5BARDELKBHCQDDIBYUPM";

    @Test
    void pagesHaveTheRecordedContentLengths() {
        assertEquals(225, length(LambdaWebErrorPages.INVALID_ENDPOINT));
        assertEquals(264, length(LambdaWebErrorPages.UNSUPPORTED_HTTP_VERSION));
        assertEquals(274, length(LambdaWebErrorPages.ENDPOINT_DISABLED));
        assertEquals(249, length(LambdaWebErrorPages.REQUEST_TIMEOUT));
        assertEquals(236, length(LambdaWebErrorPages.ORIGIN_REQUEST_ERROR));
        assertEquals(228, length(LambdaWebErrorPages.TIMEOUT_LISTEN_ERROR));
        assertEquals(228, length(LambdaWebErrorPages.INIT_CODE_ERROR));
        assertEquals(228, length(LambdaWebErrorPages.RUNTIME_EXIT));
        assertEquals(237, length(LambdaWebErrorPages.ENDPOINT_MAX_ENV_THROTTLE));
        assertEquals(237, length(LambdaWebErrorPages.ENDPOINT_REQUEST_THROTTLE));
        assertEquals(180, LambdaWebErrorPages.MALFORMED_REQUEST_HTML.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test
    void pageNamesTheStatusAndTheRequestId() {
        String html = LambdaWebErrorPages.html(LambdaWebErrorPages.INVALID_ENDPOINT, REQUEST_ID);
        assertTrue(html.startsWith("<!DOCTYPE html>\n<html>\n<head>\n    <title>HTTP Error 404</title>\n"), html);
        assertTrue(html.contains("    <h1>404 Not Found</h1>\n    <p>The requested endpoint could not be found.</p>\n"), html);
        assertTrue(html.endsWith("    <p>Request ID: " + REQUEST_ID + "</p>\n</body>\n</html>"), html);
    }

    @Test
    void edgeDateHasATwoDigitYear() {
        assertEquals("Thu, 15 Oct 26 23:32:32 GMT", LambdaWebErrorPages.edgeDate(Instant.parse("2026-10-15T23:32:32Z")));
    }

    private static int length(LambdaWebErrorPages.Page page) {
        return LambdaWebErrorPages.html(page, REQUEST_ID).getBytes(StandardCharsets.UTF_8).length;
    }
}
