package io.floci.aws.services.appsync.events;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.services.appsync.graphql.auth.AppSyncAuth;
import io.floci.aws.services.appsync.graphql.auth.LambdaAuthorizerCache;
import io.floci.aws.services.appsync.graphql.auth.LambdaAuthorizerResult;
import io.floci.aws.services.lambda.LambdaService;
import io.floci.aws.services.lambda.model.InvocationType;
import io.floci.aws.services.lambda.model.InvokeResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Runs an Events API's {@code AWS_LAMBDA} authorizer. The function receives
 * {@code {authorizationToken, requestContext{apiId, accountId, requestId, operation,
 * channelNamespaceName, channel}, requestHeaders}}, with the namespace and channel null for
 * {@code EVENT_CONNECT}, and answers {@code {isAuthorized, ttlOverride, handlerContext}}.
 *
 * <p>A result is cached per API, operation, channel and token for {@code ttlOverride} seconds, or
 * the provider's {@code authorizerResultTtlInSeconds} when the function returns none; 0 means not
 * cached. A subscribe to a wildcard channel is cached under the literal wildcard channel. A failed,
 * timed-out or malformed invocation is a denial.
 */
@ApplicationScoped
public class EventsLambdaAuthorizer {

    private static final Logger LOG = Logger.getLogger(EventsLambdaAuthorizer.class);
    private static final int TIMEOUT_SECONDS = 10;
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() { };

    private final LambdaService lambdaService;
    private final LambdaAuthorizerCache cache;
    private final ObjectMapper objectMapper;

    @Inject
    public EventsLambdaAuthorizer(LambdaService lambdaService, LambdaAuthorizerCache cache, ObjectMapper objectMapper) {
        this.lambdaService = lambdaService;
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    /** The details of one authorization a Lambda authorizer is asked for. */
    public record Request(String apiId, String accountId, String region, EventsOperation operation,
                          String channelNamespaceName, String channel, String authorizationToken,
                          Map<String, String> requestHeaders) {
    }

    /** Returns the authorizer's {@code handlerContext}, or throws Unauthorized for a denial. */
    public Map<String, Object> authorize(Request request, Map<String, Object> lambdaConfig) {
        String token = request.authorizationToken();
        if (lambdaConfig == null || token == null || token.isBlank()) {
            throw AppSyncAuth.unauthorized();
        }
        Object expression = lambdaConfig.get("identityValidationExpression");
        if (expression != null && !Pattern.compile(String.valueOf(expression)).matcher(token).matches()) {
            throw AppSyncAuth.unauthorized();
        }
        String cacheKey = cacheKey(request);
        Optional<LambdaAuthorizerResult> cached = cache.get(request.apiId(), cacheKey);
        if (cached.isPresent()) {
            return requireAuthorized(cached.get());
        }
        Object uri = lambdaConfig.get("authorizerUri");
        if (uri == null) {
            throw AppSyncAuth.unauthorized();
        }
        InvokeResult result;
        try {
            byte[] payload = objectMapper.writeValueAsBytes(event(request));
            String functionRef = String.valueOf(uri);
            result = CompletableFuture.supplyAsync(() -> invoke(request.region(), functionRef, payload))
                    .orTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .join();
        } catch (Exception e) {
            LOG.debugv(e, "Events Lambda authorizer invoke failed for API {0}", request.apiId());
            throw AppSyncAuth.unauthorized();
        }
        if (result == null || result.getFunctionError() != null || result.getPayload() == null) {
            throw AppSyncAuth.unauthorized();
        }
        LambdaAuthorizerResult parsed = parse(result.getPayload());
        cache.put(request.apiId(), cacheKey, parsed, effectiveTtl(parsed.ttlOverride(),
                intValue(lambdaConfig.get("authorizerResultTtlInSeconds"))));
        return requireAuthorized(parsed);
    }

    Map<String, Object> event(Request request) {
        Map<String, Object> requestContext = new LinkedHashMap<>();
        requestContext.put("apiId", request.apiId());
        requestContext.put("accountId", request.accountId());
        requestContext.put("requestId", UUID.randomUUID().toString());
        requestContext.put("operation", request.operation().name());
        requestContext.put("channelNamespaceName", request.channelNamespaceName());
        requestContext.put("channel", request.channel());
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("authorizationToken", request.authorizationToken());
        event.put("requestContext", requestContext);
        event.put("requestHeaders", request.requestHeaders() == null ? Map.of() : request.requestHeaders());
        return event;
    }

    private InvokeResult invoke(String region, String functionRef, byte[] payload) {
        if (AwsArnUtils.isArn(functionRef)) {
            return lambdaService.invokeArn(functionRef, payload, InvocationType.RequestResponse);
        }
        return lambdaService.invoke(region, functionRef, payload, InvocationType.RequestResponse);
    }

    LambdaAuthorizerResult parse(byte[] payload) {
        Map<String, Object> body;
        try {
            body = objectMapper.readValue(payload, OBJECT_MAP);
        } catch (Exception e) {
            LOG.debugv(e, "Events Lambda authorizer returned an unreadable result");
            throw AppSyncAuth.unauthorized();
        }
        if (body == null || !body.containsKey("isAuthorized")) {
            throw AppSyncAuth.unauthorized();
        }
        Object authorized = body.get("isAuthorized");
        boolean allowed = Boolean.TRUE.equals(authorized) || "true".equals(String.valueOf(authorized));
        Integer ttlOverride = body.get("ttlOverride") instanceof Number n ? n.intValue() : null;
        return new LambdaAuthorizerResult(allowed, List.of(), handlerContext(body.get("handlerContext")),
                ttlOverride, payload.length);
    }

    /** The handler context is a flat map of strings; nested values are dropped, as AppSync allows none. */
    private static Map<String, Object> handlerContext(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> flat = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (entry.getKey() != null && value != null && !(value instanceof Map) && !(value instanceof List)) {
                flat.put(String.valueOf(entry.getKey()), value);
            }
        }
        return flat;
    }

    static int effectiveTtl(Integer ttlOverride, int providerTtl) {
        if (ttlOverride != null) {
            return Math.max(ttlOverride, 0);
        }
        return Math.max(providerTtl, 0);
    }

    static String cacheKey(Request request) {
        return request.operation().name() + "\0" + (request.channel() == null ? "" : request.channel())
                + "\0" + request.authorizationToken();
    }

    private static Map<String, Object> requireAuthorized(LambdaAuthorizerResult result) {
        if (!result.authorized()) {
            throw AppSyncAuth.unauthorized();
        }
        return result.resolverContext();
    }

    private static int intValue(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException e) {
                LOG.debugv("Ignoring a non-numeric authorizerResultTtlInSeconds: {0}", value);
            }
        }
        return 0;
    }
}
