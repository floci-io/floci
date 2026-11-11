package io.floci.aws.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;
import org.jboss.resteasy.reactive.server.spi.ServerHttpRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResteasyRequestsTest {

    private static MultivaluedMap<String, String> uriInfoMap(ContainerRequestContext ctx) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        map.add("a", "b");
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(map);
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        return map;
    }

    private static ResteasyReactiveContainerRequestContext reactiveContext(String query) {
        ResteasyReactiveContainerRequestContext ctx = mock(ResteasyReactiveContainerRequestContext.class);
        ResteasyReactiveRequestContext request = mock(ResteasyReactiveRequestContext.class);
        ServerHttpRequest serverRequest = mock(ServerHttpRequest.class);
        when(serverRequest.query()).thenReturn(query);
        when(request.serverRequest()).thenReturn(serverRequest);
        when(ctx.getServerRequestContext()).thenReturn(request);
        return ctx;
    }

    @Test
    void syntheticContextReadsUriInfo() {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        MultivaluedMap<String, String> map = uriInfoMap(ctx);

        assertSame(map, ResteasyRequests.queryParameters(ctx));
        assertNull(ResteasyRequests.of(ctx));
    }

    @Test
    void noQueryStringSkipsTheMap() {
        ResteasyReactiveContainerRequestContext ctx = reactiveContext(null);

        assertTrue(ResteasyRequests.queryParameters(ctx).isEmpty());
        verify(ctx, never()).getUriInfo();
    }

    @Test
    void queryStringReadsUriInfo() {
        ResteasyReactiveContainerRequestContext ctx = reactiveContext("a=b");
        MultivaluedMap<String, String> map = uriInfoMap(ctx);

        assertSame(map, ResteasyRequests.queryParameters(ctx));
    }
}
