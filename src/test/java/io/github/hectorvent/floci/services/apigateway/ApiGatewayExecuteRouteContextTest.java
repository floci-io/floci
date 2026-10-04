package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ApiGatewayExecuteRouteContextTest {
    @Test
    void requestIdOverrideDoesNotReplaceExtendedIdOrChangeMidRequest() {
        ApiGatewayExecuteRouteContext context = new ApiGatewayExecuteRouteContext();
        String requestId = UUID.randomUUID().toString();
        assertEquals(requestId, context.requestIdFromOverride(requestId));
        assertEquals(requestId, context.requestIdFromOverride(UUID.randomUUID().toString()));
        assertNotEquals(requestId, context.extendedRequestId());
    }

    @Test
    void malformedUuidIsReplacedAndMarked() {
        ApiGatewayExecuteRouteContext context = new ApiGatewayExecuteRouteContext();
        String requestId = context.requestIdFromOverride("1-1-1-1-1");
        String marker = "_REPLACED_INVALID_REQUEST_ID";
        assertTrue(requestId.endsWith(marker));
        assertDoesNotThrow(() -> UUID.fromString(requestId.substring(0, requestId.length() - marker.length())));
    }
}
