package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ApiGatewayExecuteApiFilter#extractApiId(String)}.
 */
class ApiGatewayExecuteApiFilterTest {

    @Test
    void extractsApiIdFromExecuteApiSubdomain() {
        assertEquals("abc123", ApiGatewayExecuteApiFilter.extractApiId("abc123.execute-api.localhost:4566"));
    }

    @Test
    void extractsApiIdWithoutPort() {
        assertEquals("abc123", ApiGatewayExecuteApiFilter.extractApiId("abc123.execute-api.localhost"));
    }

    @Test
    void extractsApiIdWithRegion() {
        assertEquals("abc123", ApiGatewayExecuteApiFilter.extractApiId("abc123.execute-api.us-east-1.localhost:4566"));
    }

    @Test
    void extractsApiIdFromAwsDomain() {
        assertEquals("abc123", ApiGatewayExecuteApiFilter.extractApiId("abc123.execute-api.us-east-1.amazonaws.com"));
    }

    @Test
    void returnsNullForPlainLocalhost() {
        assertNull(ApiGatewayExecuteApiFilter.extractApiId("localhost:4566"));
    }

    @Test
    void returnsNullForS3VirtualHost() {
        assertNull(ApiGatewayExecuteApiFilter.extractApiId("my-bucket.localhost:4566"));
    }

    @Test
    void returnsNullForS3ServiceHost() {
        assertNull(ApiGatewayExecuteApiFilter.extractApiId("my-bucket.s3.localhost:4566"));
    }

    @Test
    void returnsNullForNull() {
        assertNull(ApiGatewayExecuteApiFilter.extractApiId(null));
    }

    @Test
    void returnsNullForNoSubdomain() {
        assertNull(ApiGatewayExecuteApiFilter.extractApiId("execute-api.localhost:4566"));
    }
}
