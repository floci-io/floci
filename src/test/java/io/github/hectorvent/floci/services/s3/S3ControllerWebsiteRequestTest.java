package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class S3ControllerWebsiteRequestTest {

    private static HttpHeaders headers(String hostHeader) {
        HttpHeaders h = mock(HttpHeaders.class);
        when(h.getHeaderString("Host")).thenReturn(hostHeader);
        return h;
    }

    private static UriInfo uri(String requestUri) {
        UriInfo u = mock(UriInfo.class);
        when(u.getRequestUri()).thenReturn(URI.create(requestUri));
        return u;
    }

    @Test
    void detectsWebsiteFromHostHeaderOverHttp1() {
        assertTrue(S3Controller.isWebsiteRequest(
                headers("my-bucket.s3-website-us-east-1.amazonaws.com"),
                uri("http://my-bucket.s3-website-us-east-1.amazonaws.com/index.html")));
    }

    @Test
    void detectsWebsiteFromUriAuthorityWhenHostHeaderAbsentOverHttp2() {
        // Regression for the HTTP/2 review finding: no Host header, authority carried by the URI.
        assertTrue(S3Controller.isWebsiteRequest(
                headers(null),
                uri("https://my-bucket.s3-website-us-east-1.localhost:4566/index.html")));
    }

    @Test
    void nonWebsiteHttp2RequestIsNotWebsite() {
        assertFalse(S3Controller.isWebsiteRequest(
                headers(null),
                uri("https://my-bucket.s3.us-east-1.localhost:4566/key.txt")));
    }

    @Test
    void nonWebsiteHostHeaderIsNotWebsite() {
        assertFalse(S3Controller.isWebsiteRequest(
                headers("my-bucket.s3.amazonaws.com"),
                uri("http://my-bucket.s3.amazonaws.com/key.txt")));
    }
}
