package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared machinery behind floci's REST tag paths: resolve the owning service from the
 * request ARN, then apply the {@link TagHandler} wire-shape choices for that service.
 *
 * <p>AWS distinguishes these services by hostname, but floci serves every service on a single
 * port, so a tag path alone is ambiguous. Two paths need this dispatch and each has its own
 * thin controller: {@code SharedTagsController} on {@code /tags/{arn}} (API Gateway, EKS,
 * Scheduler, ...) and {@code V1TagsController} on {@code /v1/tags/{arn}} (AppSync, MSK).
 * The two sets of handlers are kept apart by the {@link V1Tags} qualifier, so a service is
 * only reachable on the path AWS actually defines for it.
 */
public class TagDispatcher {

    private final Map<String, TagHandler> handlersByServiceKey;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    public TagDispatcher(Iterable<TagHandler> handlers,
                         RegionResolver regionResolver,
                         ObjectMapper objectMapper) {
        Map<String, TagHandler> map = new HashMap<>();
        for (TagHandler h : handlers) {
            String serviceKey = h.serviceKey();
            TagHandler existing = map.putIfAbsent(serviceKey, h);
            if (existing != null) {
                throw new IllegalStateException(
                        "Duplicate TagHandler registration for service key '" + serviceKey
                                + "': " + existing.getClass().getName()
                                + " and " + h.getClass().getName());
            }
        }
        this.handlersByServiceKey = Map.copyOf(map);
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    public ObjectNode emptyObject() {
        return objectMapper.createObjectNode();
    }

    public Response listTagsForArn(HttpHeaders headers, String arn) {
        TagHandler handler = resolveHandler(arn);
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> tags = handler.listTags(region, arn);
        return Response.ok(buildListResponse(handler, tags)).build();
    }

    public Response tagResourcePost(HttpHeaders headers, String arn, String body, Response successResponse) {
        TagHandler handler = resolveHandler(arn);
        if (handler.tagResourceUsesPut()) {
            throw new AwsException("MethodNotAllowedException",
                    "POST is not supported for " + handler.serviceKey() + " tag resources; use PUT.", 405);
        }
        return doTagResource(headers, handler, arn, body, successResponse);
    }

    public Response tagResourcePut(HttpHeaders headers, String arn, String body, Response successResponse) {
        TagHandler handler = resolveHandler(arn);
        if (!handler.tagResourceUsesPut()) {
            throw new AwsException("MethodNotAllowedException",
                    "PUT is not supported for " + handler.serviceKey() + " tag resources; use POST.", 405);
        }
        return doTagResource(headers, handler, arn, body, successResponse);
    }

    public Response doTagResource(HttpHeaders headers, TagHandler handler, String arn,
                                  String body, Response successResponse) {
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

    public Response untagResourceForArn(HttpHeaders headers, UriInfo uriInfo, String arn, Response successResponse) {
        return untagResourceForArn(headers, uriInfo, arn, successResponse, resolveHandler(arn));
    }

    public Response untagResourceForArn(HttpHeaders headers, UriInfo uriInfo, String arn,
                                        Response successResponse, TagHandler handler) {
        String region = regionResolver.resolveRegion(headers);
        List<String> tagKeys = readTagKeys(handler, uriInfo);
        handler.untagResource(region, arn, tagKeys);
        return successResponse;
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
                if (handler.strictTagValidation() && (!k.isTextual() || !v.isTextual())) {
                    throw new AwsException("ValidationException",
                            "1 validation error detected: Tag entries at '" + key
                                    + "' must contain string Key and Value members", 400);
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
            tagNode.fields().forEachRemaining(e -> {
                if (handler.strictTagValidation() && !e.getValue().isTextual()) {
                    throw new AwsException("ValidationException",
                            "1 validation error detected: Tag values at '" + key + "' must be strings", 400);
                }
                tags.put(e.getKey(), e.getValue().asText());
            });
        }
        return tags;
    }

    private List<String> readTagKeys(TagHandler handler, UriInfo uriInfo) {
        String paramName = handler.tagKeysQueryName();
        List<String> values = uriInfo.getQueryParameters().get(paramName);
        if (handler.strictTagValidation() && !handler.allowEmptyTagKeys()
                && (values == null || values.isEmpty())) {
            throw new AwsException("ValidationException",
                    "1 validation error detected: Value null at '" + paramName + "' failed to satisfy constraint: Member must not be null", 400);
        }
        return (values == null) ? List.of() : List.copyOf(values);
    }

    public String readResourceArn(String body) {
        try {
            JsonNode node = objectMapper.readTree((body == null || body.isBlank()) ? "{}" : body);
            return node.path("resourceArn").asText(null);
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    public TagHandler resolveHandler(String arn) {
        String serviceKey;
        try {
            serviceKey = AwsArnUtils.parse(arn).service();
        } catch (IllegalArgumentException e) {
            throw new AwsException("BadRequestException",
                    "Invalid resource ARN: " + arn, 400);
        }
        TagHandler handler = handlersByServiceKey.get(serviceKey);
        if (handler == null) {
            // Surface an unregistered service as an invalid-ARN error so floci's
            // internal routing isn't leaked to the client.
            throw new AwsException("BadRequestException",
                    "Invalid resource ARN: " + arn, 400);
        }
        return handler;
    }
}
