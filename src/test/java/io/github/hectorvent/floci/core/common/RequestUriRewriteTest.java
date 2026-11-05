package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class RequestUriRewriteTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "plain", "x%25y", "trailing%25", "a%252Fb", "a%2Fb", "a%20b", "a%3Fb%23c", "caf%C3%A9",
            "a//b", "a/./b", "a/../b", "a//x%25y"
    })
    void escapesTheRoutingPathOnceAndPreservesOtherUriComponents(String suffix) {
        ContainerRequestContext requestContext = mock(ContainerRequestContext.class);
        URI intendedUri = URI.create("http://example.test:4566/files/" + suffix + "?q=a%2525%26b");

        RequestUriRewrite.apply(requestContext, intendedUri);

        ArgumentCaptor<URI> capturedUri = ArgumentCaptor.forClass(URI.class);
        verify(requestContext).setRequestUri(capturedUri.capture());
        URI routingUri = capturedUri.getValue();
        assertEquals(intendedUri.getRawPath(), routingUri.getPath());
        assertEquals(intendedUri.getRawQuery(), routingUri.getRawQuery());
        assertEquals(intendedUri.getScheme(), routingUri.getScheme());
        assertEquals(intendedUri.getRawAuthority(), routingUri.getRawAuthority());
    }
}
