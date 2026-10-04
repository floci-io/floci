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
    private PreSignedUrlGenerator presignGenerator;

    @BeforeEach
    void setUp() {
        resourceInfo = mock(ResourceInfo.class);
        doReturn(S3Controller.class).when(resourceInfo).getResourceClass();
        presignGenerator = mock(PreSignedUrlGenerator.class);
        when(presignGenerator.shouldValidateSignatures()).thenReturn(true);
        filter = new S3HeaderSignatureFilter(null, presignGenerator, null, null, resourceInfo);
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
        ResourceInfo nonS3Info = mock(ResourceInfo.class);
        doReturn(Object.class).when(nonS3Info).getResourceClass();
        S3HeaderSignatureFilter nonS3Filter =
                new S3HeaderSignatureFilter(null, presignGenerator, null, null, nonS3Info);
        when(ctx.getHeaderString("Authorization")).thenReturn("Malformed Header");

        nonS3Filter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsMalformedAuthorizationHeaderWhenValidationDisabled() throws IOException {
        S3HeaderSignatureFilter permissiveFilter =
                new S3HeaderSignatureFilter(null, null, null, null, resourceInfo);
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("X Credential=111122223333/20261001/us-east-1/s3/aws4_request");

        permissiveFilter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void allowsSigV4AAuthorizationHeaderWhenValidationDisabled() throws IOException {
        S3HeaderSignatureFilter permissiveFilter =
                new S3HeaderSignatureFilter(null, null, null, null, resourceInfo);
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("AWS4-ECDSA-P256-SHA256 Credential=111122223333/20261001/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc");

        permissiveFilter.filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    void abortsSigV4AAuthorizationHeaderWhenValidationEnabled() throws IOException {
        when(ctx.getHeaderString("Authorization"))
                .thenReturn("AWS4-ECDSA-P256-SHA256 Credential=111122223333/20261001/us-east-1/s3/aws4_request, SignedHeaders=host, Signature=abc");

        filter.filter(ctx);

        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        Response response = captor.getValue();
        assertEquals(400, response.getStatus());
        String entity = (String) response.getEntity();
        assertTrue(entity.contains("<Code>AuthorizationHeaderMalformed</Code>"), entity);
        assertTrue(entity.contains("The authorization header you provided is invalid."), entity);
    }
}
