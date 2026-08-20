package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwsRestJsonErrorHeaderFilterTest {

    @Test
    void doesNotAddRestJsonHeaderToJsonProtocolErrorForDualProtocolService() {
        ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);
        ServiceDescriptor dualProtocolService = mock(ServiceDescriptor.class);
        when(dualProtocolService.supportsProtocol(ServiceProtocol.REST_JSON)).thenReturn(true);

        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getProperty(AwsProtocolClaimFilter.CLAIM_PROPERTY)).thenReturn(
                new ProtocolClaim(WireProtocol.AWS_JSON_1_1, dualProtocolService, "Operation", null));

        ContainerResponseContext response = mock(ContainerResponseContext.class);
        when(response.getStatus()).thenReturn(400);
        when(response.getHeaderString("X-Amzn-Errortype")).thenReturn(null);

        new AwsRestJsonErrorHeaderFilter(catalog).filter(request, response);

        verify(response, never()).getEntity();
        verify(response, never()).getHeaders();
    }
}
