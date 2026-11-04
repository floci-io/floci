package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.jboss.resteasy.reactive.server.core.Deployment;
import org.jboss.resteasy.reactive.server.core.ResteasyReactiveRequestContext;
import org.jboss.resteasy.reactive.server.spi.ServerHttpRequest;
import org.jboss.resteasy.reactive.server.spi.ServerHttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequestUriRewriteTest {

    @ParameterizedTest
    @ValueSource(strings = {"plain", "x%25y", "trailing%25", "a%252Fb", "a%2Fb", "a%20b", "a%3Fb%23c", "caf%C3%A9"})
    void keepsDecodedPathsAndRawUrisConsistent(String suffix) {
        ContainerRequestContext context = requestContext();
        URI intendedUri = URI.create("http://example.test/files/" + suffix + "?q=a%2525%26b");

        RequestUriRewrite.apply(context, intendedUri);

        UriInfo uriInfo = context.getUriInfo();
        assertEquals(intendedUri.getPath(), uriInfo.getPath());
        assertEquals(intendedUri, uriInfo.getRequestUri());
        assertEquals(intendedUri, uriInfo.getRequestUriBuilder().buildFromEncoded());
        assertEquals(URI.create("http://example.test/files/" + suffix), uriInfo.getAbsolutePath());
        assertEquals(uriInfo.getAbsolutePath(), uriInfo.getAbsolutePathBuilder().buildFromEncoded());
        assertEquals(URI.create("http://example.test/"), uriInfo.getBaseUri());
    }

    @Test
    void successiveRewritesDoNotAccumulateEscapes() {
        ContainerRequestContext context = requestContext();
        RequestUriRewrite.apply(context, URI.create("http://example.test/first/x%25y?q=%2525"));
        URI secondUri = context.getUriInfo().getRequestUriBuilder()
                .replacePath("/second/x%25y").buildFromEncoded();

        RequestUriRewrite.apply(context, secondUri);

        assertEquals("/second/x%y", context.getUriInfo().getPath());
        assertEquals("/second/x%25y", context.getUriInfo().getRequestUri().getRawPath());
        assertEquals("q=%2525", context.getUriInfo().getRequestUri().getRawQuery());
    }

    private static ContainerRequestContext requestContext() {
        Deployment deployment = mock(Deployment.class);
        when(deployment.getPrefix()).thenReturn("");
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getRequestScheme()).thenReturn("http");
        when(request.getRequestHostAndPort()).thenReturn("example.test");
        ResteasyReactiveRequestContext serverContext = new ResteasyReactiveRequestContext(deployment, null, null, null) {
            @Override
            public ServerHttpRequest serverRequest() {
                return request;
            }

            @Override
            public ServerHttpResponse serverResponse() {
                return null;
            }

            @Override
            protected Executor getEventLoop() {
                return null;
            }

            @Override
            public Runnable registerTimer(long millis, Runnable task) {
                return null;
            }

            @Override
            public boolean resumeExternalProcessing() {
                return false;
            }

            @Override
            protected void setQueryParamsFrom(String uri) {
                // Transport query parsing is exercised by the HTTP integration tests.
            }
        };
        return serverContext.getContainerRequestContext().setPreMatch(true);
    }
}
