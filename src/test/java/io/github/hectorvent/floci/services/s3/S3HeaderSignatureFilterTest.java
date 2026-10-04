package io.github.hectorvent.floci.services.s3;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3HeaderSignatureFilterTest {

    private S3HeaderSignatureFilter filter;
    private ContainerRequestContext ctx;
    private ResourceInfo resourceInfo;

    @BeforeEach
    void setUp() {
        filter = new S3HeaderSignatureFilter(null, null, null, null);
        resourceInfo = mock(ResourceInfo.class);
        doReturn(S3Controller.class).when(resourceInfo).getResourceClass();
        filter.resourceInfo = resourceInfo;
        ctx = mock(ContainerRequestContext.class);
    }

    @Test
    void abortsWhenAuthorizationHeaderDoesNotStartWithAlgorithm() throws IOException {
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("X Credential=111122223333/20261001/us-east-1/s3/aws4_request");

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(400, response.getStatus());
        String entity = (String) response.getEntity();
        assertTrue(entity.contains("<Code>AuthorizationHeaderMalformed</Code>"), entity);
        assertTrue(entity.contains("The authorization header you provided is invalid."), entity);
    }

    @Test
    void allowsRequestWhenAuthorizationHeaderAbsent() throws IOException {
        when(ctx.getHeaderString("Authorization")).thenReturn(null);

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsRequestWhenAuthorizationHeaderBlank() throws IOException {
        when(ctx.getHeaderString("Authorization")).thenReturn("   ");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void ignoresRequestWhenNotRoutedToS3() throws IOException {
        doReturn(Object.class).when(resourceInfo).getResourceClass();
        when(ctx.getHeaderString("Authorization")).thenReturn("Malformed Header");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsSigV4AAuthorizationHeader() throws IOException {
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("AWS4-ECDSA-P256-SHA256 Credential=111122223333/20261001/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc");

        filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }
}
