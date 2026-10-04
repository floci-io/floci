package io.github.hectorvent.floci.services.apigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.iam.IamPolicyEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RestLambdaAuthorizerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-03T00:00:00Z");
    private RestLambdaAuthorizer authorizer;

    @BeforeEach
    void createAuthorizer() {
        when(clock.instant()).thenReturn(now);
        authorizer = new RestLambdaAuthorizer(mapper, new IamPolicyEvaluator(mapper), clock);
    }

    @Test
    void expiresAtTtlBoundaryAndZeroTtlDoesNotCache() {
        RestLambdaAuthorizer.Result result = new RestLambdaAuthorizer.Result("principal", "{}", Map.of("claims", "trusted"));
        RestLambdaAuthorizer.CacheKey key = key("account", "region", "api", "stage", "deployment", "token", 10);
        cache(key, result);
        when(clock.instant()).thenReturn(now.plusSeconds(9));
        assertSame(result, authorizer.get(key));
        when(clock.instant()).thenReturn(now.plusSeconds(10));
        assertNull(authorizer.get(key));
        RestLambdaAuthorizer.CacheKey disabled = key("account", "region", "api", "stage", "deployment", "token", 0);
        cache(disabled, result);
        assertNull(authorizer.get(disabled));
    }

    @Test
    void cacheIsScopedAndFlushOnlyRemovesTargetStage() {
        RestLambdaAuthorizer.CacheKey target = key("account", "region", "api", "stage", "deployment", "token", 300);
        List<RestLambdaAuthorizer.CacheKey> others = List.of(
                key("other", "region", "api", "stage", "deployment", "token", 300),
                key("account", "other", "api", "stage", "deployment", "token", 300),
                key("account", "region", "other", "stage", "deployment", "token", 300),
                key("account", "region", "api", "other", "deployment", "token", 300));
        RestLambdaAuthorizer.Result result = new RestLambdaAuthorizer.Result("principal", "{}", Map.of());
        cache(target, result);
        others.forEach(key -> cache(key, result));
        assertNull(authorizer.get(key("account", "region", "api", "stage", "new-deployment", "token", 300)));
        assertNull(authorizer.get(key("account", "region", "api", "stage", "deployment", "other-token", 300)));
        authorizer.flush(target.scope());
        assertNull(authorizer.get(target));
        others.forEach(key -> assertSame(result, authorizer.get(key)));
    }

    @Test
    void policyArraysWildcardsAndConditionsUseIamEvaluation() throws Exception {
        byte[] payload = mapper.writeValueAsBytes(Map.of("principalId", "principal", "policyDocument", Map.of(
                "Version", "2012-10-17", "Statement", List.of(Map.of("Effect", "Allow",
                        "Action", List.of("execute-api:Invoke"), "Resource", List.of("*/GET/allowed/*"),
                        "Condition", Map.of("IpAddress", Map.of("aws:SourceIp", "192.0.2.0/24")))))));
        RestLambdaAuthorizer.Result result = authorizer.parse(payload);
        assertTrue(authorizer.permits(result, "api/stage/GET/allowed/child", Map.of("aws:SourceIp", List.of("192.0.2.10"))));
        assertFalse(authorizer.permits(result, "api/stage/GET/other/child", Map.of("aws:SourceIp", List.of("192.0.2.10"))));
        assertFalse(authorizer.permits(result, "api/stage/GET/allowed/child", Map.of("aws:SourceIp", List.of("198.51.100.10"))));
    }

    @Test
    void scalarContextRetainsTypesAndIsImmutable() throws Exception {
        byte[] payload = mapper.writeValueAsBytes(Map.of("principalId", "principal", "context",
                Map.of("tenant-id", "trusted", "number", 2, "boolean", true), "policyDocument",
                Map.of("Statement", Map.of("Effect", "Allow", "Action", "execute-api:Invoke", "Resource", "*"))));
        RestLambdaAuthorizer.Result result = authorizer.parse(payload);
        assertEquals(Map.of("tenant-id", "trusted", "number", 2, "boolean", true), result.context());
        assertThrows(UnsupportedOperationException.class, () -> result.context().put("claims", "forged"));
        assertTrue(authorizer.permits(result, "method", Map.of()));
    }

    @Test
    void flushInvalidatesOnlyPendingInvocationsInItsScope() {
        RestLambdaAuthorizer.CacheKey target = key("account", "region", "api", "stage", "deployment", "token", 300);
        RestLambdaAuthorizer.CacheKey other = key("account", "region", "api", "other", "deployment", "token", 300);
        RestLambdaAuthorizer.Result result = new RestLambdaAuthorizer.Result("principal", "{}", Map.of());
        try (RestLambdaAuthorizer.Invocation beforeFlush = authorizer.beginInvocation(target);
             RestLambdaAuthorizer.Invocation unaffected = authorizer.beginInvocation(other)) {
            authorizer.flush(target.scope());
            cache(target, result);
            beforeFlush.cache(new RestLambdaAuthorizer.Result("stale", "{}", Map.of()));
            unaffected.cache(result);
            assertSame(result, authorizer.get(target));
            assertSame(result, authorizer.get(other));
        }
    }

    @Test
    void closedInvocationCannotInsertResult() {
        RestLambdaAuthorizer.CacheKey key = key("account", "region", "api", "stage", "deployment", "token", 300);
        RestLambdaAuthorizer.Invocation invocation = authorizer.beginInvocation(key);
        invocation.close();
        invocation.cache(new RestLambdaAuthorizer.Result("principal", "{}", Map.of()));
        assertNull(authorizer.get(key));
    }

    private void cache(RestLambdaAuthorizer.CacheKey key, RestLambdaAuthorizer.Result result) {
        try (RestLambdaAuthorizer.Invocation invocation = authorizer.beginInvocation(key)) {
            invocation.cache(result);
        }
    }

    private static RestLambdaAuthorizer.CacheKey key(String account, String region, String api, String stage,
                                                     String deployment, String token, int ttl) {
        return new RestLambdaAuthorizer.CacheKey(new RestLambdaAuthorizer.Scope(account, region, api, stage),
                deployment, "authorizer", "uri", "TOKEN", "method.request.header.Authorization", ttl, List.of(token));
    }
}
