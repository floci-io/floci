package io.floci.aws.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.core.CurrentRequestManager;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ResteasyReactiveContainerRequestContext;
import org.jboss.resteasy.reactive.server.spi.ServerHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResteasyRequestsTest {

    private static UriInfo uriInfo(String value) {
        MultivaluedMap<String, String> map = new MultivaluedHashMap<>();
        map.add("a", value);
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(map);
        return uriInfo;
    }

    private static MultivaluedMap<String, String> uriInfoMap(ContainerRequestContext ctx) {
        UriInfo uriInfo = uriInfo("b");
        when(ctx.getUriInfo()).thenReturn(uriInfo);
        return uriInfo.getQueryParameters();
    }

    private static ResteasyReactiveContainerRequestContext reactiveContext(ResteasyReactiveRequestContext request) {
        ResteasyReactiveContainerRequestContext ctx = mock(ResteasyReactiveContainerRequestContext.class);
        when(ctx.getServerRequestContext()).thenReturn(request);
        return ctx;
    }

    private static ResteasyReactiveRequestContext requestContext(String query, UriInfo uriInfo) {
        ResteasyReactiveRequestContext request = mock(ResteasyReactiveRequestContext.class);
        ServerHttpRequest serverRequest = mock(ServerHttpRequest.class);
        when(serverRequest.query()).thenReturn(query);
        when(request.serverRequest()).thenReturn(serverRequest);
        when(request.getUriInfo()).thenReturn(uriInfo);
        Map<String, Object> properties = new HashMap<>();
        when(request.getProperty(anyString())).thenAnswer(call -> properties.get(call.<String>getArgument(0)));
        doAnswer(call -> properties.put(call.getArgument(0), call.getArgument(1)))
                .when(request).setProperty(anyString(), any());
        return request;
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
        ResteasyReactiveContainerRequestContext ctx = reactiveContext(requestContext(null, null));

        assertTrue(ResteasyRequests.queryParameters(ctx).isEmpty());
        verify(ctx, never()).getUriInfo();
    }

    @Test
    void queryStringReadsUriInfo() {
        UriInfo uriInfo = uriInfo("b");
        ResteasyReactiveContainerRequestContext ctx = reactiveContext(requestContext("a=b", uriInfo));

        assertSame(uriInfo.getQueryParameters(), ResteasyRequests.queryParameters(ctx));
    }

    @Test
    void queryStringIsReadOncePerUriInfo() {
        UriInfo uriInfo = uriInfo("b");
        ResteasyReactiveContainerRequestContext ctx = reactiveContext(requestContext("a=b", uriInfo));

        assertSame(ResteasyRequests.queryParameters(ctx), ResteasyRequests.queryParameters(ctx));
        verify(uriInfo, times(1)).getQueryParameters();
    }

    @Test
    void rewrittenUriInfoReadsTheQueryAgain() {
        UriInfo first = uriInfo("b");
        ResteasyReactiveRequestContext request = requestContext("a=b", first);
        ResteasyReactiveContainerRequestContext ctx = reactiveContext(request);
        assertSame(first.getQueryParameters(), ResteasyRequests.queryParameters(ctx));

        UriInfo second = uriInfo("c");
        when(request.getUriInfo()).thenReturn(second);

        assertSame(second.getQueryParameters(), ResteasyRequests.queryParameters(ctx));
    }

    @Test
    void injectedUriInfoWithoutQueryStringSkipsTheMap() {
        UriInfo uriInfo = mock(UriInfo.class);
        CurrentRequestManager.set(requestContext(null, null));
        try {
            assertTrue(ResteasyRequests.queryParameters(uriInfo).isEmpty());
            verify(uriInfo, never()).getQueryParameters();
        } finally {
            CurrentRequestManager.set(null);
        }
    }

    @Test
    void injectedUriInfoWithQueryStringReadsTheMap() {
        UriInfo uriInfo = uriInfo("b");
        CurrentRequestManager.set(requestContext("a=b", uriInfo));
        try {
            assertSame(uriInfo.getQueryParameters(), ResteasyRequests.queryParameters(uriInfo));
        } finally {
            CurrentRequestManager.set(null);
        }
    }
}
