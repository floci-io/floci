package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the API Gateway REST proxy-integration event collapses a duplicate request header to its
 * LAST value in the single-value {@code headers} map while keeping every value in
 * {@code multiValueHeaders}, matching the AWS proxy-integration input format
 * (multiValueHeaders.header=[v1,v2] -> headers.header="v2").
 */
class ProxyEventHeaderCollapseTest {

    private ApiGatewayExecuteController controller;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        controller = new ApiGatewayExecuteController(
                null, null, null,
                regionResolver, new ObjectMapper(), null,
                null, null, null, null
        );
    }

    @Test
    void duplicateHeaderCollapsesToLastValueButMultiValueKeepsBoth() {
        MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();
        requestHeaders.add("X-Dup", "first");
        requestHeaders.add("X-Dup", "second");
        HttpHeaders headers = mock(HttpHeaders.class);
        when(headers.getRequestHeaders()).thenReturn(requestHeaders);

        ObjectNode event = new ObjectMapper().createObjectNode();
        controller.putSingleValueHeaders(event, headers);
        controller.putMultiValueHeaders(event, headers);

        // Single-value map keeps the LAST value (was the first before the fix).
        assertEquals("second", event.get("headers").get("X-Dup").asText());

        // multiValueHeaders keeps every value in order.
        assertEquals(2, event.get("multiValueHeaders").get("X-Dup").size());
        assertEquals("first", event.get("multiValueHeaders").get("X-Dup").get(0).asText());
        assertEquals("second", event.get("multiValueHeaders").get("X-Dup").get(1).asText());
    }
}
