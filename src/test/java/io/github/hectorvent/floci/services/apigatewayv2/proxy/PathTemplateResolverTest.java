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

    @Test
    void substitutesGreedyProxyPlaceholder() {
        // AWS-style {proxy+} (greedy) in IntegrationUri resolves against the same
        // "proxy" key extracted from a "ANY /wallet/{proxy+}" route key.
        assertEquals("http://x/users/123",
                PathTemplateResolver.resolve("http://x/{proxy+}", Map.of("proxy", "users/123")));
    }

    @Test
    void substitutesGreedyNamedPlaceholder() {
        assertEquals("/v1/path/a/b/c",
                PathTemplateResolver.resolve("/v1/path/{tail+}", Map.of("tail", "a/b/c")));
    }

    @Test
    void mixesGreedyAndNonGreedyPlaceholders() {
        assertEquals("/users/u-1/files/a/b",
                PathTemplateResolver.resolve("/users/{user}/files/{path+}",
                        Map.of("user", "u-1", "path", "a/b")));
    }

    @Test
    void missingGreedyPlaceholderBecomesEmpty() {
        assertEquals("/x/", PathTemplateResolver.resolve("/x/{missing+}", Map.of()));
    }
}
