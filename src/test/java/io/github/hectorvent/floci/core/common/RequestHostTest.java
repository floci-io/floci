package io.github.hectorvent.floci.core.common;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.HostAndPort;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.when;

class RequestHostTest {

    private static final URI HTTP2_URI =
            URI.create("https://e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:4566/hello.txt");

    @Test
    void prefersTheHostHeader() {
        assertEquals("viewer.example.test", RequestHost.of("viewer.example.test", HTTP2_URI));
    }

    @Test
    void fallsBackToTheUriAuthorityWithoutAHostHeader() {
        assertEquals("e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:4566", RequestHost.of(null, HTTP2_URI));
        assertEquals("e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:4566", RequestHost.of("  ", HTTP2_URI));
    }

    @Test
    void answersNullWithNeitherAHostHeaderNorAnAuthority() {
        assertNull(RequestHost.of(null, (URI) null));
        assertNull(RequestHost.of(null, URI.create("/relative/path")));
    }

    @Test
    void readsARequestContext() {
        UriInfo uriInfo = Mockito.mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(HTTP2_URI);
        ContainerRequestContext context = Mockito.mock(ContainerRequestContext.class);
        when(context.getUriInfo()).thenReturn(uriInfo);

        assertEquals("e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:4566", RequestHost.of(context));

        when(context.getHeaderString("Host")).thenReturn("viewer.example.test");
        assertEquals("viewer.example.test", RequestHost.of(context));
    }

    @Test
    void readsTheHttp2AuthorityOfAVertxRequest() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        when(request.authority()).thenReturn(
                HostAndPort.create("e1z2x3c4v5b6n7.cloudfront.localhost.floci.io", 4566));

        assertEquals("e1z2x3c4v5b6n7.cloudfront.localhost.floci.io:4566", RequestHost.of(request));

        when(request.getHeader("Host")).thenReturn("viewer.example.test");
        assertEquals("viewer.example.test", RequestHost.of(request));
    }

    @Test
    void answersNullForAVertxRequestWithoutAnAuthority() {
        assertNull(RequestHost.of(Mockito.mock(HttpServerRequest.class)));
    }
}
