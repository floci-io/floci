package io.floci.aws.services.cognito;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsProtocolClaimFilter;
import io.floci.aws.core.common.ProtocolClaim;
import io.floci.aws.core.common.ServiceDescriptor;
import io.floci.aws.core.common.ServiceProtocol;
import io.floci.aws.core.common.WireProtocol;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @ParameterizedTest
    @CsvSource({"https://ui.example.test,HTTPS://UI.EXAMPLE.TEST,true",
            "https://other.example.test,https://ui.example.test,false", "https://other.example.test,*,true"})
    void responseMergesExposedHeadersWithoutCaseInsensitiveDuplicates(String origin, String allowedOrigin,
                                                                    boolean exposesConfiguredHeader) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().cognito().enabled()).thenReturn(true);
        when(config.security().extraCorsAllowedOrigins()).thenReturn(Optional.of(List.of(allowedOrigin)));
        when(config.security().extraCorsExposeHeaders()).thenReturn(Optional.of(
                List.of("x-visible-header", "date", " X-AMZN-REQUESTID ", "x-visible-header")));
        CognitoCorsFilter filter = new CognitoCorsFilter(() -> config);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        when(request.getUriInfo()).thenReturn(uri);
        when(uri.getPath()).thenReturn("");
        when(request.getHeaderString("Origin")).thenReturn(origin);
        ServiceDescriptor service = new ServiceDescriptor("cognito-idp", "cognito", true, true,
                null, null, 0L, null, ServiceProtocol.JSON, Set.of(ServiceProtocol.JSON),
                Set.of("AWSCognitoIdentityProviderService."), Set.of("cognito-idp"), Set.of(), Set.of(), Set.of());
        when(request.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY)).thenReturn(
                new ProtocolClaim(WireProtocol.AWS_JSON_1_1, service, "ListUserPools",
                        "AWSCognitoIdentityProviderService.ListUserPools"));
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.add("Access-Control-Expose-Headers", "X-Existing, date");
        headers.add("Access-Control-Expose-Headers", "x-existing");
        when(response.getHeaders()).thenReturn(headers);

        filter.filter(request, response);

        Set<String> expected = new HashSet<>(Set.of("X-Existing", "Date", "x-amzn-RequestId", "x-amzn-ErrorType",
                "x-amzn-ErrorMessage"));
        if (exposesConfiguredHeader) {
            expected.add("x-visible-header");
        }
        List<String> exposedHeaders = Arrays.asList(headers.getFirst("Access-Control-Expose-Headers")
                .toString().split(",\\s*"));
        assertEquals(expected.size(), exposedHeaders.size());
        assertEquals(expected, new HashSet<>(exposedHeaders));
        assertEquals(1, headers.get("Access-Control-Expose-Headers").size());
    }
}
