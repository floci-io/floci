package io.github.hectorvent.floci.services.apigatewayv2.proxy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies {@link RequestParameterMapper} applies the documented
 * action × target combinations (overwrite|append|remove × path|header|querystring)
 * with source values resolved through {@link ContextValueResolver}.
 */
class RequestParameterMapperTest {

    private RequestParameterMapper mapper;
    private RequestContext ctx;
    private ProxyRequestBuilder builder;

    @BeforeEach
    void setUp() {
        mapper = new RequestParameterMapper(new ContextValueResolver());
        ctx = new RequestContext("api1", "$default", "GET", "/wallet/balance", "balance",
                "ANY /wallet/{proxy+}", "req-1", "1.2.3.4",
                Map.of("Original-Header", "kept"),
                Map.of("page", "2"),
                Map.of("proxy", "balance"),
                null,
                Map.of("userId", "u-42", "email", "a@b.com"),
                Map.of());
        builder = new ProxyRequestBuilder("http://backend.local/foo", "GET");
    }

    @Test
    void overwriteHeaderWithLiteralValue() {
        mapper.apply(Map.of("overwrite:header.Host", "wallet.internal"), builder, ctx);
        assertEquals("wallet.internal", builder.headers().get("Host").get(0));
    }

    @Test
    void appendHeaderResolvesContextClaim() {
        mapper.apply(Map.of("append:header.x-user-id", "$context.authorizer.claims.userId"), builder, ctx);
        assertEquals("u-42", builder.headers().get("x-user-id").get(0));
    }

    @Test
    void appendHeaderToExistingHeaderAccumulates() {
        builder.overwriteHeader("x-trace", "first");
        mapper.apply(Map.of("append:header.x-trace", "second"), builder, ctx);
        assertEquals(2, builder.headers().get("x-trace").size());
        assertTrue(builder.headers().get("x-trace").contains("first"));
        assertTrue(builder.headers().get("x-trace").contains("second"));
    }

    @Test
    void removeHeaderDropsHeaderEvenWhenSourceIsLiteral() {
        builder.overwriteHeader("Authorization", "Bearer xxx");
        mapper.apply(Map.of("remove:header.Authorization", ""), builder, ctx);
        assertFalse(builder.headers().containsKey("Authorization"));
    }

    @Test
    void overwritePathReplacesPathPreservingHostAndScheme() {
        // Source contains $request.path.proxy that must be substituted
        mapper.apply(Map.of("overwrite:path", "/public/$request.path.proxy"), builder, ctx);
        assertEquals("http://backend.local/public/balance", builder.url());
    }

    @Test
    void overwritePathWithLiteral() {
        mapper.apply(Map.of("overwrite:path", "/literal/path"), builder, ctx);
        assertEquals("http://backend.local/literal/path", builder.url());
    }

    @Test
    void overwriteQuerystringSetsParam() {
        mapper.apply(Map.of("overwrite:querystring.tier", "gold"), builder, ctx);
        assertEquals("gold", builder.queryParams().get("tier").get(0));
    }

    @Test
    void appendQuerystringResolvesContextValue() {
        mapper.apply(Map.of("append:querystring.user", "$context.authorizer.claims.userId"), builder, ctx);
        assertEquals("u-42", builder.queryParams().get("user").get(0));
    }

    @Test
    void removeQuerystringDropsParam() {
        builder.overwriteQuery("debug", "true");
        mapper.apply(Map.of("remove:querystring.debug", ""), builder, ctx);
        assertFalse(builder.queryParams().containsKey("debug"));
    }

    @Test
    void unresolvedSourceSkipsHeader() {
        // append:header.X with $context.authorizer.claims.missing should NOT add the header
        mapper.apply(Map.of("append:header.x-missing", "$context.authorizer.claims.missing"),
                builder, ctx);
        assertFalse(builder.headers().containsKey("x-missing"));
    }

    @Test
    void multipleParametersAppliedInOrder() {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("overwrite:header.Host", "wallet.internal");
        params.put("append:header.x-user-id", "$context.authorizer.claims.userId");
        params.put("overwrite:path", "/public/$request.path.proxy");
        mapper.apply(params, builder, ctx);
        assertEquals("wallet.internal", builder.headers().get("Host").get(0));
        assertEquals("u-42", builder.headers().get("x-user-id").get(0));
        assertEquals("http://backend.local/public/balance", builder.url());
    }

    @Test
    void emptyParamsIsNoOp() {
        mapper.apply(Map.of(), builder, ctx);
        mapper.apply(null, builder, ctx);
        assertEquals("http://backend.local/foo", builder.url());
        assertTrue(builder.headers().isEmpty());
    }

    @Test
    void malformedKeyIsSkipped() {
        // No colon in key — should be skipped silently
        mapper.apply(Map.of("not-a-valid-key", "value"), builder, ctx);
        assertEquals("http://backend.local/foo", builder.url());
    }
}
