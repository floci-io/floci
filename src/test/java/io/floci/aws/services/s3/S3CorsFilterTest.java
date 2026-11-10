package io.floci.aws.services.s3;

import jakarta.enterprise.inject.IllegalProductException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ResourceInfo;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class S3CorsFilterTest {

    @Test
    void leavesTheResponseToARequestRoutingTurnedAwayAlone() {
        // A 415 from RESTEasy's media-type dispatch leaves a ResourceInfo whose producer CDI refuses.
        ResourceInfo noMethodChosen = mock(ResourceInfo.class);
        when(noMethodChosen.getResourceClass()).thenThrow(new IllegalProductException("no method chosen"));
        S3Service s3Service = mock(S3Service.class);
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        when(request.getHeaderString("Origin")).thenReturn("https://example.com");
        ContainerResponseContext response = mock(ContainerResponseContext.class);

        new S3CorsFilter(s3Service, noMethodChosen).filter(request, response);

        verifyNoInteractions(s3Service);
        verify(response, never()).getHeaders();
    }
}
