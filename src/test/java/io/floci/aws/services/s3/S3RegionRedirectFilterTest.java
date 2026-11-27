package io.floci.aws.services.s3;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The off switch, {@code floci.services.s3.region-redirect: false}, serves every bucket to every Region. */
class S3RegionRedirectFilterTest {

    private static final String EU_WEST_1_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260325/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=test";

    @Test
    void withTheRedirectOffABucketIsServedToARequestSignedForAnotherRegion() {
        ContainerRequestContext request = getBucketSignedForEuWest1();

        filter(false).filter(request);

        verify(request, never()).abortWith(any());
    }

    @Test
    void withTheRedirectOnTheSameRequestIsPermanentlyRedirected() {
        ContainerRequestContext request = getBucketSignedForEuWest1();

        filter(true).filter(request);

        ArgumentCaptor<Response> response = ArgumentCaptor.forClass(Response.class);
        verify(request).abortWith(response.capture());
        assertEquals(301, response.getValue().getStatus());
        assertEquals("us-east-1", response.getValue().getHeaderString("x-amz-bucket-region"));
    }

    @SuppressWarnings("unchecked")
    private static S3RegionRedirectFilter filter(boolean regionRedirect) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().s3().regionRedirect()).thenReturn(regionRedirect);
        Instance<EmulatorConfig> configs = mock(Instance.class);
        when(configs.get()).thenReturn(config);
        S3Service s3Service = mock(S3Service.class);
        when(s3Service.findBucketRegion("bucket")).thenReturn(Optional.of("us-east-1"));
        ResourceInfo resourceInfo = mock(ResourceInfo.class);
        when(resourceInfo.getResourceClass()).thenAnswer(invocation -> S3Controller.class);
        return new S3RegionRedirectFilter(s3Service, configs, new RegionResolver("us-east-1", "000000000000"),
                resourceInfo);
    }

    private static ContainerRequestContext getBucketSignedForEuWest1() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedHashMap<String, String> path = new MultivaluedHashMap<>();
        path.putSingle("bucket", "bucket");
        when(uriInfo.getPathParameters()).thenReturn(path);
        when(uriInfo.getQueryParameters()).thenReturn(new MultivaluedHashMap<>());
        when(request.getUriInfo()).thenReturn(uriInfo);
        when(request.getMethod()).thenReturn("GET");
        when(request.getHeaderString("Authorization")).thenReturn(EU_WEST_1_AUTH);
        return request;
    }
}
