package io.floci.aws.services.apigatewayv2.proxy;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies {@link ContextValueResolver} resolves the documented HTTP API v2
 * source value expressions: $context.*, $request.*, $stageVariables.*, and literal pass-through.
 */
class ContextValueResolverTest {

    private RequestContext ctx(Map<String, Object> claims, Map<String, Object> authContext,
                                Map<String, String> headers, Map<String, String> query,
                                Map<String, String> pathParams) {
        return new RequestContext("api1", "$default", "GET", "/foo", "foo", "ANY /foo",
                "req-1", "1.2.3.4", headers, query, pathParams, null, claims, authContext);
    }

    private RequestContext withBody(String body) {
        return new RequestContext("api1", "$default", "POST", "/foo", "foo", "POST /foo",
                "req-1", "1.2.3.4", Map.of(), Map.of(), Map.of(),
                body == null ? null : body.getBytes(StandardCharsets.UTF_8), Map.of(), Map.of());
    }

    private RequestContext withVariables(Map<String, String> stageVariables, Map<String, String> contextVariables) {
        return ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of()).withVariables(stageVariables, contextVariables);
    }

    @Test
    void literalValuePassesThrough() {
        ContextValueResolver r = new ContextValueResolver();
        assertEquals("wallet.internal",
                r.resolve("wallet.internal", ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void contextAuthorizerClaimsResolves() {
        ContextValueResolver r = new ContextValueResolver();
        RequestContext c = ctx(Map.of("userId", "u-123", "email", "a@b.com"),
                Map.of(), Map.of(), Map.of(), Map.of());
        assertEquals("u-123", r.resolve("$context.authorizer.claims.userId", c));
        assertEquals("a@b.com", r.resolve("$context.authorizer.claims.email", c));
    }

    @Test
    void contextAuthorizerClaimsMissingReturnsNull() {
        ContextValueResolver r = new ContextValueResolver();
        assertNull(r.resolve("$context.authorizer.claims.missing",
                ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void contextAuthorizerArbitraryKeyResolves() {
        ContextValueResolver r = new ContextValueResolver();
        RequestContext c = ctx(Map.of(), Map.of("principalId", "p-1"), Map.of(), Map.of(), Map.of());
        assertEquals("p-1", r.resolve("$context.authorizer.principalId", c));
    }

    @Test
    void contextRequestIdResolves() {
        ContextValueResolver r = new ContextValueResolver();
        assertEquals("req-1",
                r.resolve("$context.requestId", ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void contextSourceIpResolves() {
        ContextValueResolver r = new ContextValueResolver();
        assertEquals("1.2.3.4",
                r.resolve("$context.identity.sourceIp", ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void requestHeaderResolvesCaseInsensitive() {
        ContextValueResolver r = new ContextValueResolver();
        RequestContext c = ctx(Map.of(), Map.of(), Map.of("X-Forwarded-For", "5.6.7.8"), Map.of(), Map.of());
        assertEquals("5.6.7.8", r.resolve("$request.header.X-Forwarded-For", c));
        assertEquals("5.6.7.8", r.resolve("$request.header.x-forwarded-for", c));
    }

    @Test
    void requestQuerystringResolves() {
        ContextValueResolver r = new ContextValueResolver();
        RequestContext c = ctx(Map.of(), Map.of(), Map.of(), Map.of("page", "2"), Map.of());
        assertEquals("2", r.resolve("$request.querystring.page", c));
    }

    @Test
    void requestPathResolves() {
        ContextValueResolver r = new ContextValueResolver();
        RequestContext c = ctx(Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of("proxy", "users/123", "id", "42"));
        assertEquals("users/123", r.resolve("$request.path.proxy", c));
        assertEquals("42", r.resolve("$request.path.id", c));
    }

    @Test
    void unknownExpressionReturnsNull() {
        ContextValueResolver r = new ContextValueResolver();
        assertNull(r.resolve("$context.unknown",
                ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void nullExpressionReturnsNull() {
        ContextValueResolver r = new ContextValueResolver();
        assertNull(r.resolve(null, ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void requestBodyResolvesToTheWholeBody() {
        ContextValueResolver r = new ContextValueResolver();
        assertEquals("{\"order\":{\"id\":\"o-42\"}}",
                r.resolve("$request.body", withBody("{\"order\":{\"id\":\"o-42\"}}")));
    }

    @Test
    void emptyRequestBodyReturnsNull() {
        ContextValueResolver r = new ContextValueResolver();
        assertNull(r.resolve("$request.body", withBody(null)));
        assertNull(r.resolve("$request.body", withBody("")));
    }

    @Test
    void requestBodyPathResolvesScalarsAndJson() {
        ContextValueResolver r = new ContextValueResolver();
        RequestContext c = withBody("{\"order\":{\"id\":\"o-42\",\"qty\":3,\"tags\":[\"a\",\"b\"]}}");
        assertEquals("o-42", r.resolve("$request.body.order.id", c));
        assertEquals("3", r.resolve("$request.body.order.qty", c));
        assertEquals("a", r.resolve("$request.body.order.tags[0]", c));
        assertEquals("[\"a\",\"b\"]", r.resolve("$request.body.order.tags", c));
    }

    @Test
    void requestBodyPathMissingOrUnreadableReturnsNull() {
        ContextValueResolver r = new ContextValueResolver();
        assertNull(r.resolve("$request.body.order.missing", withBody("{\"order\":{\"id\":\"o-42\"}}")));
        assertNull(r.resolve("$request.body.order.id", withBody("not json")));
        assertNull(r.resolve("$request.body.order.id", withBody(null)));
    }

    @Test
    void stageVariableResolves() {
        ContextValueResolver r = new ContextValueResolver();
        RequestContext c = withVariables(Map.of("backend", "blue"), null);
        assertEquals("blue", r.resolve("$stageVariables.backend", c));
        assertNull(r.resolve("$stageVariables.missing", c));
    }

    @Test
    void stageVariableWithoutStageVariablesReturnsNull() {
        ContextValueResolver r = new ContextValueResolver();
        assertNull(r.resolve("$stageVariables.backend", ctx(Map.of(), Map.of(), Map.of(), Map.of(), Map.of())));
    }

    @Test
    void contextVariableResolves() {
        ContextValueResolver r = new ContextValueResolver();
        RequestContext c = withVariables(null, Map.of("stage", "prod", "routeKey", "ANY /foo"));
        assertEquals("prod", r.resolve("$context.stage", c));
        assertEquals("ANY /foo", r.resolve("$context.routeKey", c));
        assertNull(r.resolve("$context.unknown", c));
    }
}
