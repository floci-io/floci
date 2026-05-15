package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link PathTemplateResolver} substitutes {paramName} placeholders
 * in IntegrationUri templates against the captured path parameters of the
 * matched route.
 */
class PathTemplateResolverTest {

    @Test
    void substitutesProxyPlaceholder() {
        assertEquals("http://x/users/123",
                PathTemplateResolver.resolve("http://x/{proxy}", Map.of("proxy", "users/123")));
    }

    @Test
    void substitutesNamedPlaceholder() {
        assertEquals("/v1/orders/42",
                PathTemplateResolver.resolve("/v1/orders/{id}", Map.of("id", "42")));
    }

    @Test
    void substitutesMultiplePlaceholders() {
        assertEquals("/users/u-1/orders/42",
                PathTemplateResolver.resolve("/users/{user}/orders/{id}",
                        Map.of("user", "u-1", "id", "42")));
    }

    @Test
    void missingPlaceholderBecomesEmpty() {
        assertEquals("/x/", PathTemplateResolver.resolve("/x/{missing}", Map.of()));
    }

    @Test
    void noPlaceholdersPassThrough() {
        assertEquals("/static", PathTemplateResolver.resolve("/static", Map.of()));
    }

    @Test
    void nullTemplateReturnsNull() {
        assertNull(PathTemplateResolver.resolve(null, Map.of()));
    }

    @Test
    void nullPathParamsTreatedAsEmpty() {
        assertEquals("/x/", PathTemplateResolver.resolve("/x/{anything}", null));
    }
}
