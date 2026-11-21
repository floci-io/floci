package io.floci.aws.services.iam;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CloudFrontOriginAccessControlAuthorizationTest {

    @Test
    void updateRouteIgnoresMisleadingActionHints() {
        IamActionRegistry registry = new IamActionRegistry();
        assertEquals("cloudfront:UpdateOriginAccessControl", registry.resolveRoute("cloudfront", updateRequest()));
    }

    @ParameterizedTest
    @CsvSource({
            "aws,us-east-1",
            "aws-cn,cn-north-1",
            "aws-us-gov,us-gov-west-1"
    })
    void resourceUsesDecodedMatchedIdAndAccountWithoutARegion(String partition, String region) {
        ResourceArnBuilder resources = new ResourceArnBuilder(new ObjectMapper());
        assertEquals(List.of("arn:" + partition + ":cloudfront::123456789012:origin-access-control/EOWNED"),
                resources.buildResources("cloudfront", "cloudfront:UpdateOriginAccessControl",
                        updateRequest(), region, "123456789012", partition));
    }

    private static ContainerRequestContext updateRequest() {
        ContainerRequestContext request = mock(ContainerRequestContext.class);
        UriInfo uri = mock(UriInfo.class);
        URI target = URI.create("http://localhost/2020-05-31/origin-access-control/%45OWNED/config"
                + "?Action=GetOriginAccessControl&Id=EOTHER");
        when(request.getUriInfo()).thenReturn(uri);
        when(request.getMethod()).thenReturn("PUT");
        when(request.getHeaderString("X-Amz-Target")).thenReturn("CloudFront.GetOriginAccessControl");
        when(uri.getRequestUri()).thenReturn(target);
        when(uri.getBaseUri()).thenReturn(URI.create("http://localhost/"));
        when(uri.getPath()).thenReturn(target.getPath());
        MultivaluedHashMap<String, String> query = new MultivaluedHashMap<>();
        query.add("Action", "GetOriginAccessControl");
        query.add("Id", "EOTHER");
        when(uri.getQueryParameters()).thenReturn(query);
        return request;
    }
}
