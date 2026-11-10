package io.floci.aws.core.common;

import jakarta.enterprise.inject.IllegalProductException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.Test;

import static io.floci.aws.testing.InlineMocks.inlineMock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AwsRestJsonErrorHeaderFilterTest {

    @Test
    void doesNotAddRestJsonHeaderToJsonProtocolErrorForDualProtocolService() {
        ResolvedServiceCatalog catalog = mock(ResolvedServiceCatalog.class);
        ServiceDescriptor dualProtocolService = inlineMock(ServiceDescriptor.class);
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

    @Test
    void leavesTheErrorOfARequestRoutingTurnedAwayAlone() {
        // A 415 from RESTEasy's media-type dispatch leaves a ResourceInfo whose producer CDI refuses.
        ResourceInfo noMethodChosen = mock(ResourceInfo.class);
        when(noMethodChosen.getResourceClass()).thenThrow(new IllegalProductException("no method chosen"));
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        ContainerResponseContext response = mock(ContainerResponseContext.class);
        when(response.getStatus()).thenReturn(415);

        AwsRestJsonErrorHeaderFilter filter = new AwsRestJsonErrorHeaderFilter(mock(ResolvedServiceCatalog.class));
        filter.resourceInfo = noMethodChosen;
        filter.filter(request, response);

        verify(response, never()).getHeaders();
    }
}
