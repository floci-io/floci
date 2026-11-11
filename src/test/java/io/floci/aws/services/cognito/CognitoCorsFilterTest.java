package io.floci.aws.services.cognito;

import io.floci.aws.config.EmulatorConfig;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CognitoCorsFilterTest {

    @ParameterizedTest
    @CsvSource({"false,false", "true,true"})
    void disablingCognitoOrCorsSkipsThePublicPreflight(boolean serviceEnabled, boolean corsDisabled) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cognito().enabled()).thenReturn(serviceEnabled);
        when(config.security().disableCorsHeaders()).thenReturn(corsDisabled);
        CognitoCorsFilter filter = new CognitoCorsFilter(() -> config);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("");
        when(request.getMethod()).thenReturn("OPTIONS");
        when(request.getHeaderString("Origin")).thenReturn("http://localhost:3000");
        when(request.getHeaderString("Access-Control-Request-Method")).thenReturn("POST");
        when(request.getHeaderString("Access-Control-Request-Headers")).thenReturn("content-type,x-amz-target");

        filter.filter(request);

        verify(request, never()).abortWith(any(Response.class));
    }
}
