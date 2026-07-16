package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IamConditionContextResolverTest {

    private final IamConditionContextResolver resolver = new IamConditionContextResolver();

    @Test
    void resolvesS3ListBucketQueryConditionContext() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);
        UriInfo uriInfo = mock(UriInfo.class);
        MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
        query.add("prefix", "my_namespace/table/");
        query.add("delimiter", "/");
        query.add("max-keys", "100");

        when(containerRequest.getUriInfo()).thenReturn(uriInfo);
        when(uriInfo.getQueryParameters()).thenReturn(query);

        Map<String, String> conditions = resolver.resolve("s3", "s3:ListBucket", containerRequest);

        assertEquals("my_namespace/table/", conditions.get("s3:prefix"));
        assertEquals("/", conditions.get("s3:delimiter"));
        assertEquals("100", conditions.get("s3:max-keys"));
    }

    @Test
    void s3BucketListConditionContextReturnsNullWhenNoSupportedQueryParametersArePresent() {
        assertNull(resolver.s3BucketListConditionContext(new MultivaluedHashMap<>()));
    }

    @Test
    void resolveReturnsNullForUnsupportedServiceOrAction() {
        ContainerRequestContext containerRequest = mock(ContainerRequestContext.class);

        assertNull(resolver.resolve("lambda", "lambda:InvokeFunction", containerRequest));
        assertNull(resolver.resolve("s3", "s3:GetObject", containerRequest));
    }
}
