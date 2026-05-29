package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link ContextValueResolver} resolves the documented HTTP API v2
 * source value expressions: $context.*, $request.*, and literal pass-through.
 */
class ContextValueResolverTest {

    private RequestContext ctx(Map<String, Object> claims, Map<String, Object> authContext,
                                Map<String, String> headers, Map<String, String> query,
                                Map<String, String> pathParams) {
        return new RequestContext("api1", "$default", "GET", "/foo", "foo", "ANY /foo",
                "req-1", "1.2.3.4", headers, query, pathParams, null, claims, authContext);
    }

    @Test
    void literalValuePassesThrough() {
        var r = new ContextValueResolver();
        assertEquals("wallet.internal",
                r.resolve("wallet.internal", ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void contextAuthorizerClaimsResolves() {
        var r = new ContextValueResolver();
        var c = ctx(Map.of("userId", "u-123", "email", "a@b.com"),
                Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals("u-123", r.resolve("$context.authorizer.claims.userId", c));
        assertEquals("a@b.com", r.resolve("$context.authorizer.claims.email", c));
    }

    @Test
    void contextAuthorizerClaimsMissingReturnsNull() {
        var r = new ContextValueResolver();
        assertNull(r.resolve("$context.authorizer.claims.missing",
                ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void contextAuthorizerArbitraryKeyResolves() {
        var r = new ContextValueResolver();
        var c = ctx(Map.of(), Map.of("principalId", "p-1"), Map.of(), Map.of(), Map.of());
        assertEquals("p-1", r.resolve("$context.authorizer.principalId", c));
    }

    @Test
    void contextRequestIdResolves() {
        var r = new ContextValueResolver();
        assertEquals("req-1",
                r.resolve("$context.requestId", ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void contextSourceIpResolves() {
        var r = new ContextValueResolver();
        assertEquals("1.2.3.4",
                r.resolve("$context.identity.sourceIp", ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void requestHeaderResolvesCaseInsensitive() {
        var r = new ContextValueResolver();
        var c = ctx(Map.of(), Map.of(), Map.of("X-Forwarded-For", "5.6.7.8"), Map.of(), Map.of());
        assertEquals("5.6.7.8", r.resolve("$request.header.X-Forwarded-For", c));
        assertEquals("5.6.7.8", r.resolve("$request.header.x-forwarded-for", c));
    }

    @Test
    void requestQuerystringResolves() {
        var r = new ContextValueResolver();
        var c = ctx(Map.of(), Map.of(), Map.of(), Map.of("page", "2"), Map.of());
        assertEquals("2", r.resolve("$request.querystring.page", c));
    }

    @Test
    void requestPathResolves() {
        var r = new ContextValueResolver();
        var c = ctx(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("proxy", "users/123", "id", "42"));
        assertEquals("users/123", r.resolve("$request.path.proxy", c));
        assertEquals("42", r.resolve("$request.path.id", c));
    }

    @Test
    void unknownExpressionReturnsNull() {
        var r = new ContextValueResolver();
        assertNull(r.resolve("$context.unknown",
                ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void nullExpressionReturnsNull() {
        var r = new ContextValueResolver();
        assertNull(r.resolve(null, ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }
}
