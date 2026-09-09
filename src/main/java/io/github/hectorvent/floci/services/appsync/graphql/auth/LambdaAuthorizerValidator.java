package io.github.hectorvent.floci.services.appsync.graphql.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@ApplicationScoped
public class LambdaAuthorizerValidator {

    private static final Logger LOG = Logger.getLogger(LambdaAuthorizerValidator.class);
    static final int MAX_RESOLVER_CONTEXT_BYTES = 5 * 1024 * 1024;
    private static final int TIMEOUT_SECONDS = 10;

    private final LambdaService lambdaService;
    private final LambdaAuthorizerCache cache;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaAuthorizerValidator(LambdaService lambdaService,
                                     LambdaAuthorizerCache cache,
                                     ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    public LambdaAuthorizerResult authorize(
            String apiId,
            String authorizationToken,
            Map<String, Object> lambdaConfig,
            AuthRequestInfo info
    ) {
        if (lambdaConfig == null) {
            throw AppSyncAuth.unauthorized();
        }
        String expression = string(lambdaConfig.get("identityValidationExpression"));
        if (expression != null && !Pattern.compile(expression).matcher(authorizationToken).matches()) {
            throw AppSyncAuth.unauthorized();
        }

        var cached = cache.get(apiId, authorizationToken);
        if (cached.isPresent()) {
            return requireAuthorized(cached.get());
        }

        String uri = string(lambdaConfig.get("authorizerUri"));
        if (uri == null) {
            throw AppSyncAuth.unauthorized();
        }

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("authorizationToken", authorizationToken);
        Map<String, Object> requestContext = new LinkedHashMap<>();
        requestContext.put("apiId", apiId);
        requestContext.put("accountId", info.accountId());
        requestContext.put("requestId", info.requestId());
        requestContext.put("queryString", info.query());
        requestContext.put("operationName", info.operationName());
        requestContext.put("variables", info.variables());
        event.put("requestContext", requestContext);
        event.put("requestHeaders", info.requestHeaders());

        InvokeResult invokeResult;
        try {
            byte[] payload = objectMapper.writeValueAsBytes(event);
            invokeResult = CompletableFuture.supplyAsync(() -> lambdaService.invoke(
                            info.region(), uri, payload, InvocationType.RequestResponse))
                    .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            LOG.debugv(e, "Lambda authorizer invoke failed for API {0}", apiId);
            throw AppSyncAuth.unauthorized();
        }
        if (invokeResult == null || invokeResult.getFunctionError() != null || invokeResult.getPayload() == null) {
            throw AppSyncAuth.unauthorized();
        }

        LambdaAuthorizerResult parsed = parse(invokeResult.getPayload());
        int apiTtl = intValue(lambdaConfig.get("authorizerResultTtlInSeconds"), 0);
        int ttl = effectiveTtl(parsed.ttlOverride(), apiTtl);
        cache.put(apiId, authorizationToken, parsed, ttl);
        return requireAuthorized(parsed);
    }

    static int effectiveTtl(Integer ttlOverride, int apiTtl) {
        if (ttlOverride != null) {
            return ttlOverride <= 0 ? 0 : ttlOverride;
        }
        return Math.max(apiTtl, 0);
    }

    private LambdaAuthorizerResult requireAuthorized(LambdaAuthorizerResult result) {
        if (!result.authorized()) {
            throw AppSyncAuth.unauthorized();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    LambdaAuthorizerResult parse(byte[] payload) {
        Map<String, Object> body;
        try {
            body = objectMapper.readValue(payload, Map.class);
        } catch (Exception e) {
            throw AppSyncAuth.unauthorized();
        }
        if (body == null || body.isEmpty() || !body.containsKey("isAuthorized")) {
            throw AppSyncAuth.unauthorized();
        }
        Object authorizedValue = body.get("isAuthorized");
        boolean authorized = Boolean.TRUE.equals(authorizedValue) || "true".equals(String.valueOf(authorizedValue));
        List<String> denied = new ArrayList<>();
        Object deniedFields = body.get("deniedFields");
        if (deniedFields instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    denied.add(String.valueOf(item));
                }
            }
        }
        Map<String, Object> resolverContext = flattenResolverContext(body.get("resolverContext"));
        Integer ttlOverride = CognitoAuthValidator.asLong(body.get("ttlOverride")) == null
                ? null
                : CognitoAuthValidator.asLong(body.get("ttlOverride")).intValue();
        return new LambdaAuthorizerResult(authorized, denied, resolverContext, ttlOverride, payload.length);
    }

    Map<String, Object> flattenResolverContext(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map || value instanceof List) {
                continue;
            }
            if (entry.getKey() != null && value != null) {
                flat.put(String.valueOf(entry.getKey()), value);
            }
        }
        try {
            int size = objectMapper.writeValueAsBytes(flat).length;
            if (size > MAX_RESOLVER_CONTEXT_BYTES) {
                throw AppSyncAuth.unauthorized();
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw AppSyncAuth.unauthorized();
        }
        return flat;
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int intValue(Object value, int fallback) {
        Long parsed = CognitoAuthValidator.asLong(value);
        return parsed == null ? fallback : parsed.intValue();
    }
}
