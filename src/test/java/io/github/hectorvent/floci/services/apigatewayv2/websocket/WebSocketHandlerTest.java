package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link WebSocketHandler} host parsing utilities.
 */
class WebSocketHandlerTest {

    @Test
    void parsesApiIdAndRegionFromSubdomainHost() {
        WebSocketHandler.ExecuteApiHost h =
                WebSocketHandler.parseExecuteApiHost("abc123.execute-api.ap-northeast-2.localhost:4566");
        assertNotNull(h);
        assertEquals("abc123", h.apiId());
        assertEquals("ap-northeast-2", h.region());
    }

    @Test
    void regionIsNullWhenHostOmitsIt() {
        WebSocketHandler.ExecuteApiHost h =
                WebSocketHandler.parseExecuteApiHost("abc123.execute-api.localhost:4566");
        assertNotNull(h);
        assertEquals("abc123", h.apiId());
        assertNull(h.region());
    }

    @Test
    void returnsNullForNonExecuteApiHosts() {
        assertNull(WebSocketHandler.parseExecuteApiHost("my-bucket.localhost:4566"));
        assertNull(WebSocketHandler.parseExecuteApiHost("localhost:4566"));
        assertNull(WebSocketHandler.parseExecuteApiHost(null));
    }
}
