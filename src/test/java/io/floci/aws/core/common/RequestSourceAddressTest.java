package io.floci.aws.core.common;

import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestSourceAddressTest {

    @Test
    void aClientSentForwardedForIsNotTheSourceAddress() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(54321, "127.0.0.1"));
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7");

        assertEquals("127.0.0.1", RequestSourceAddress.of(request));
    }

    /** Behind a proxy Quarkus trusts, Quarkus makes the forwarded address the request's remote address. */
    @Test
    void theRemoteAddressQuarkusGivesTheRequestIsTheSourceAddress() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(request.remoteAddress()).thenReturn(SocketAddress.inetSocketAddress(443, "203.0.113.7"));

        assertEquals("203.0.113.7", RequestSourceAddress.of(request));
    }

    @Test
    void aConnectionWithoutAnAddressNamesNone() {
        assertNull(RequestSourceAddress.of(mock(HttpServerRequest.class)));
    }
}
