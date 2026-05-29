package io.github.hectorvent.floci.services.apigateway;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link ApiGatewayExecuteController#extractV2PathParams(String, String)}
 * captures path parameters correctly for both greedy ({proxy+}) and non-greedy
 * ({proxy}) route templates, and that repeated invocations against the same
 * route key reuse the cached compiled Pattern (correctness check; the cache
 * itself is an internal implementation detail).
 */
class ApiGatewayExecuteControllerTest {

    @Test
    void capturesGreedyProxyMultiSegment() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "ANY /wallet/{proxy+}", "/wallet/users/123/orders");
        assertEquals("users/123/orders", p.get("proxy"));
    }

    @Test
    void capturesNonGreedyNamedParam() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "GET /users/{id}", "/users/42");
        assertEquals("42", p.get("id"));
    }

    @Test
    void capturesMultipleNamedParams() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "GET /users/{user}/orders/{order}", "/users/u-1/orders/o-2");
        assertEquals("u-1", p.get("user"));
        assertEquals("o-2", p.get("order"));
    }

    @Test
    void capturesMixedGreedyAndNamedParams() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "ANY /users/{user}/files/{path+}", "/users/u-1/files/a/b/c");
        assertEquals("u-1", p.get("user"));
        assertEquals("a/b/c", p.get("path"));
    }

    @Test
    void noMatchReturnsEmptyMap() {
        Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                "GET /users/{id}", "/orders/42");
        assertTrue(p.isEmpty());
    }

    @Test
    void nullRouteKeyReturnsEmptyMap() {
        assertTrue(ApiGatewayExecuteController.extractV2PathParams(null, "/x").isEmpty());
    }

    @Test
    void malformedRouteKeyReturnsEmptyMap() {
        // No method/path split — caller passed garbage.
        assertTrue(ApiGatewayExecuteController.extractV2PathParams("garbage", "/x").isEmpty());
    }

    @Test
    void repeatedCallsAgainstSameRouteAreStable() {
        // Second hit reuses the cached compiled Pattern; output must be
        // identical for the same inputs. Run hot to give the cache a chance
        // to be exercised across multiple invocations.
        String routeKey = "ANY /payments/{proxy+}";
        for (int i = 0; i < 100; i++) {
            Map<String, String> p = ApiGatewayExecuteController.extractV2PathParams(
                    routeKey, "/payments/spei/" + i);
            assertEquals("spei/" + i, p.get("proxy"));
        }
    }
}
