package io.github.hectorvent.floci.core.common;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MissingHostHeaderFilterTest {

    @Test
    void originFormTakesTheAuthorityFromTheLocalSocket() {
        assertEquals("127.0.0.1:4566", MissingHostHeaderFilter.requestAuthority(request("/_floci/health")));
    }

    @Test
    void absoluteFormTakesTheAuthorityFromTheTarget() {
        assertEquals("bucket.s3.localhost:4566",
                MissingHostHeaderFilter.requestAuthority(request("http://bucket.s3.localhost:4566/key")));
    }

    @Test
    void absoluteFormWithoutAPathTakesTheAuthorityFromTheTarget() {
        assertEquals("bucket.s3.localhost:4566",
                MissingHostHeaderFilter.requestAuthority(request("http://bucket.s3.localhost:4566")));
    }

    @Test
    void asteriskFormHasNoAuthority() {
        assertNull(MissingHostHeaderFilter.requestAuthority(request("*")));
    }

    @Test
    void malformedTargetHasNoAuthority() {
        assertNull(MissingHostHeaderFilter.requestAuthority(request("http://bucket.s3.localhost:45 66/key")));
    }

    @Test
    void localAuthorityKeepsIpv4Literal() {
        assertEquals("127.0.0.1:4566",
                MissingHostHeaderFilter.localAuthority(SocketAddress.inetSocketAddress(4566, "127.0.0.1")));
    }

    @Test
    void localAuthorityBracketsIpv6Literal() {
        assertEquals("[::1]:4566",
                MissingHostHeaderFilter.localAuthority(SocketAddress.inetSocketAddress(4566, "::1")));
    }

    @Test
    void localAuthorityLeavesBracketedIpv6Alone() {
        assertEquals("[::1]:4566",
                MissingHostHeaderFilter.localAuthority(SocketAddress.inetSocketAddress(4566, "[::1]")));
    }

    @Test
    void localAuthorityWithoutAddressIsNull() {
        assertNull(MissingHostHeaderFilter.localAuthority(null));
    }

    @Test
    void localAuthorityOfDomainSocketIsNull() {
        assertNull(MissingHostHeaderFilter.localAuthority(SocketAddress.domainSocketAddress("/tmp/floci.sock")));
    }

    private static HttpServerRequest request(String target) {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.uri()).thenReturn(target);
        when(request.localAddress()).thenReturn(SocketAddress.inetSocketAddress(4566, "127.0.0.1"));
        return request;
    }
}
