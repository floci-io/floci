package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request handling shared by every controller that serves an AWS REST tag endpoint whose
 * path is ambiguous across services.
 *
 * <p>AWS distinguishes these services by hostname; floci serves every service on a single
 * port, so the path alone cannot say which service a request belongs to. Every such request
 * is resolved from the {@code service} segment of the request ARN
 * ({@code arn:aws:<service>:<region>:<account>:<resource>}) and dispatched to the matching
 * {@link TagHandler}.
 *
 * <p>The path prefix is part of the lookup key, not just the controller's own concern:
 * services do not all hang their tag endpoints off the same prefix ({@code /tags} for API
 * Gateway v1, EKS and Scheduler; {@code /v1/tags} for AppSync and Batch; {@code /v2/tags}
 * for API Gateway v2). Keying on the prefix as well as the service keeps each controller
 * serving exactly the services AWS serves at that path, rather than every registered
 * handler.
 *
 * <p>A controller that claims one of these paths for a single service - rather than going
 * through this dispatcher - silently swallows every other service that shares it. That is
 * what {@code AppSyncController} did to AWS Batch (lex00/floci#72): Batch's
 * {@code TagResource} fell into AppSync's {@code /v1/tags/{resourceArn: .+}} catch-all and
 * came back {@code 404 GraphQL API not found}.
 */
@ApplicationScoped
public class SharedTagsDispatcher {

    private final Map<String, Map<String, TagHandler>> handlersByPrefix;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SharedTagsDispatcher(Instance<TagHandler> handlers,
                                RegionResolver regionResolver,
                                ObjectMapper objectMapper) {
        Map<String, Map<String, TagHandler>> byPrefix = new HashMap<>();
        for (TagHandler h : handlers) {
            String prefix = h.tagPathPrefix();
            String serviceKey = h.serviceKey();
            Map<String, TagHandler> forPrefix = byPrefix.computeIfAbsent(prefix, p -> new HashMap<>());
            TagHandler existing = forPrefix.putIfAbsent(serviceKey, h);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate TagHandler registration for service key '" + serviceKey
                                + "' on path prefix '" + prefix + "': " + existing.getClass().getName()
                                + " and " + h.getClass().getName());
            }
        }
        Map<String, Map<String, TagHandler>> copy = new HashMap<>();
        byPrefix.forEach((prefix, map) -> copy.put(prefix, Map.copyOf(map)));
        this.handlersByPrefix = Map.copyOf(copy);
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /** The service keys served at {@code prefix}. Exposed for routing coverage tests. */
    public java.util.Set<String> serviceKeysFor(String prefix) {
        return handlersByPrefix.getOrDefault(prefix, Map.of()).keySet();
    }

    public Response listTags(String prefix, HttpHeaders headers, String arn) {
        TagHandler handler = resolveHandler(prefix, arn);
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> tags = handler.listTags(region, arn);
        return Response.ok(buildListResponse(handler, tags)).build();
    }

    public Response tagResourceByBody(String prefix, HttpHeaders headers, String body) {
        String arn = readResourceArn(body);
        TagHandler handler = resolveHandler(prefix, arn);
        return doTagResource(headers, handler, arn, body,
                Response.ok(objectMapper.createObjectNode()).build());
    }

    public Response tagResourcePost(String prefix, HttpHeaders headers, String arn, String body) {
        TagHandler handler = resolveHandler(prefix, arn);
        if (handler.tagResourceUsesPut()) {
            throw new AwsException("MethodNotAllowedException",
                    "POST is not supported for " + handler.serviceKey() + " tag resources; use PUT.", 405);
        }
        return doTagResource(headers, handler, arn, body, Response.noContent().build());
    }

    public Response tagResourcePut(String prefix, HttpHeaders headers, String arn, String body) {
        TagHandler handler = resolveHandler(prefix, arn);
        if (!handler.tagResourceUsesPut()) {
            throw new AwsException("MethodNotAllowedException",
                    "PUT is not supported for " + handler.serviceKey() + " tag resources; use POST.", 405);
        }
        return doTagResource(headers, handler, arn, body, Response.noContent().build());
    }

    public Response untagResource(String prefix, HttpHeaders headers, UriInfo uriInfo, String arn,
                                  Response successResponse) {
        TagHandler handler = resolveHandler(prefix, arn);
        String region = regionResolver.resolveRegion(headers);
        List<String> tagKeys = readTagKeys(handler, uriInfo);
        handler.untagResource(region, arn, tagKeys);
        return successResponse;
    }

    private Response doTagResource(HttpHeaders headers, TagHandler handler, String arn, String body,
                                   Response successResponse) {
        String region = regionResolver.resolveRegion(headers);
        String effectiveBody = (body == null || body.isBlank()) ? "{}" : body;
        try {
            JsonNode node = objectMapper.readTree(effectiveBody);
            Map<String, String> tags = parseTags(handler, node);
            handler.tagResource(region, arn, tags);
            return successResponse;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            String code = handler.strictTagValidation() ? "ValidationException" : "BadRequestException";
            throw new AwsException(code, e.getMessage(), 400);
        }
    }

    private ObjectNode buildListResponse(TagHandler handler, Map<String, String> tags) {
        ObjectNode root = objectMapper.createObjectNode();
        String key = handler.tagsBodyKey();
        if (handler.tagsBodyIsList()) {
            ArrayNode arr = root.putArray(key);
            tags.forEach((k, v) -> {
                ObjectNode entry = arr.addObject();
                entry.put("Key", k);
                entry.put("Value", v);
            });
        } else {
            ObjectNode tagsNode = root.putObject(key);
            tags.forEach(tagsNode::put);
        }
        return root;
    }

    private Map<String, String> parseTags(TagHandler handler, JsonNode node) {
        Map<String, String> tags = new HashMap<>();
        String key = handler.tagsBodyKey();
        if (handler.strictTagValidation() && !node.isObject()) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Request payload must be a JSON object", 400);
        }
        JsonNode tagNode = node.get(key);
        if (tagNode == null || tagNode.isNull()) {
            if (handler.strictTagValidation()) {
                throw new AwsException("ValidationException",
                        "1 validation error detected: Value null at '" + key + "' failed to satisfy constraint: Member must not be null", 400);
            }
            return tags;
        }
        if (handler.tagsBodyIsList()) {
            if (!tagNode.isArray()) {
                if (handler.strictTagValidation()) {
                    throw new AwsException("ValidationException",
                            "1 validation error detected: Value at '" + key + "' failed to satisfy constraint: Member must be a list", 400);
                }
                return tags;
            }
            for (JsonNode entry : tagNode) {
                JsonNode k = entry.get("Key");
                JsonNode v = entry.get("Value");
                if (k == null || k.isNull() || v == null || v.isNull()) {
                    if (handler.strictTagValidation()) {
                        throw new AwsException("ValidationException",
                                "1 validation error detected: Tag entries at '" + key + "' must have non-null Key and Value", 400);
                    }
                    continue;
                }
                tags.put(k.asText(), v.asText());
            }
        } else {
            if (!tagNode.isObject()) {
                if (handler.strictTagValidation()) {
                    throw new AwsException("ValidationException",
                            "1 validation error detected: Value at '" + key + "' failed to satisfy constraint: Member must be a map", 400);
                }
                return tags;
            }
            tagNode.fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
        }
        return tags;
    }

    private List<String> readTagKeys(TagHandler handler, UriInfo uriInfo) {
        String paramName = handler.tagKeysQueryName();
        List<String> values = uriInfo.getQueryParameters().get(paramName);
        if (handler.strictTagValidation() && (values == null || values.isEmpty())) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at '" + paramName + "' failed to satisfy constraint: Member must not be null", 400);
        }
        return (values == null) ? List.of() : List.copyOf(values);
    }

    private String readResourceArn(String body) {
        try {
            JsonNode node = objectMapper.readTree((body == null || body.isBlank()) ? "{}" : body);
            return node.path("resourceArn").asText(null);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    private TagHandler resolveHandler(String prefix, String arn) {
        String serviceKey;
        try {
            serviceKey = AwsArnUtils.parse(arn).service();
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException",
                    "Invalid resource ARN: " + arn, 400);
        }
        TagHandler handler = handlersByPrefix.getOrDefault(prefix, Map.of()).get(serviceKey);
        if (handler == null) {
            // Surface an unregistered service as an invalid-ARN error so floci's
            // internal routing isn't leaked to the client.
            throw new AwsException("BadRequestException",
                    "Invalid resource ARN: " + arn, 400);
        }
        return handler;
    }
}
