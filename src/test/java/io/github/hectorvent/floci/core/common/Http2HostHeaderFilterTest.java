package io.github.hectorvent.floci.core.common;

import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Http2HostHeaderFilterTest {

    @Test
    void copiesTheAuthorityIntoTheHostHeaderOverHttp2() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        RoutingContext context = context(HttpVersion.HTTP_2, headers,
                HostAndPort.create("EXAMPLEDIST.cloudfront.localhost.floci.io", 4566));

        Http2HostHeaderFilter.applyAuthority(context);

        assertEquals("EXAMPLEDIST.cloudfront.localhost.floci.io:4566", headers.get("Host"));
        verify(context).next();
    }

    @Test
    void leavesAnHttp11RequestAlone() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        RoutingContext context = context(HttpVersion.HTTP_1_1, headers,
                HostAndPort.create("localhost", 4566));

        Http2HostHeaderFilter.applyAuthority(context);

        assertNull(headers.get("Host"));
        verify(context).next();
    }

    @Test
    void keepsAHostHeaderTheClientSent() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().set("Host", "viewer.example.test");
        RoutingContext context = context(HttpVersion.HTTP_2, headers,
                HostAndPort.create("EXAMPLEDIST.cloudfront.localhost.floci.io", 4566));

        Http2HostHeaderFilter.applyAuthority(context);

        assertEquals("viewer.example.test", headers.get("Host"));
    }

    @Test
    void toleratesARequestWithoutAnAuthority() {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        RoutingContext context = context(HttpVersion.HTTP_2, headers, null);

        Http2HostHeaderFilter.applyAuthority(context);

        assertNull(headers.get("Host"));
        verify(context).next();
    }

    private static RoutingContext context(HttpVersion version, MultiMap headers, HostAndPort authority) {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        when(request.version()).thenReturn(version);
        when(request.headers()).thenReturn(headers);
        when(request.getHeader("Host")).thenAnswer(invocation -> headers.get("Host"));
        when(request.authority()).thenReturn(authority);
        RoutingContext context = Mockito.mock(RoutingContext.class);
        when(context.request()).thenReturn(request);
        return context;
    }
}
