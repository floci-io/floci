package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatcher for AWS services that share the REST {@code /tags/{resourceArn}} path
 * (API Gateway, EventBridge Scheduler, EKS, ...).
 *
 * <p>AWS distinguishes these services by hostname, but floci serves every service on a
 * single port, so the path alone is ambiguous. This controller resolves the owning
 * service from the {@code service} segment of the request ARN
 * ({@code arn:aws:<service>:<region>:<account>:<resource>}) and dispatches to the
 * matching {@link TagHandler}.
 */
@Path("/tags")
@Produces(MediaType.APPLICATION_JSON)
public class SharedTagsController {

    private final Map<String, TagHandler> handlersByServiceKey;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SharedTagsController(Instance<TagHandler> handlers,
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

    @GET
    public Response listTagsByQuery(@Context HttpHeaders headers,
                                    @QueryParam("resourceArn") String arn) {
        return listTagsForArn(headers, arn);
    }

    @GET
    @Path("/{arn: .+}")
    public Response listTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        return listTagsForArn(headers, arn);
    }

    private Response listTagsForArn(HttpHeaders headers, String arn) {
        TagHandler handler = resolveHandler(arn);
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> tags = handler.listTags(region, arn);
        return Response.ok(buildListResponse(handler, tags)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourceByBody(@Context HttpHeaders headers, String body) {
        String arn = readResourceArn(body);
        TagHandler handler = resolveHandler(arn);
        return doTagResource(headers, handler, arn, body, Response.ok(objectMapper.createObjectNode()).build());
    }

    @POST
    @Path("/{arn: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourcePost(@Context HttpHeaders headers,
                                    @PathParam("arn") String arn,
                                    String body) {
        TagHandler handler = resolveHandler(arn);
        if (handler.tagResourceUsesPut()) {
            throw new AwsException("MethodNotAllowedException",
                    "POST is not supported for " + handler.serviceKey() + " tag resources; use PUT.", 405);
        }
        return doTagResource(headers, handler, arn, body, Response.noContent().build());
    }

    @PUT
    @Path("/{arn: .+}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourcePut(@Context HttpHeaders headers,
                                   @PathParam("arn") String arn,
                                   String body) {
        TagHandler handler = resolveHandler(arn);
        if (!handler.tagResourceUsesPut()) {
            throw new AwsException("MethodNotAllowedException",
                    "PUT is not supported for " + handler.serviceKey() + " tag resources; use POST.", 405);
        }
        return doTagResource(headers, handler, arn, body, Response.noContent().build());
    }

    private Response doTagResource(HttpHeaders headers, TagHandler handler, String arn, String body, Response successResponse) {
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

    @DELETE
    public Response untagResourceByQuery(@Context HttpHeaders headers,
                                         @Context UriInfo uriInfo,
                                         @QueryParam("resourceArn") String arn) {
        return untagResourceForArn(headers, uriInfo, arn, Response.ok(objectMapper.createObjectNode()).build());
    }

    @DELETE
    @Path("/{arn: .+}")
    public Response untagResource(@Context HttpHeaders headers,
                                   @Context UriInfo uriInfo,
                                   @PathParam("arn") String arn) {
        return untagResourceForArn(headers, uriInfo, arn, Response.noContent().build());
    }

    private Response untagResourceForArn(HttpHeaders headers, UriInfo uriInfo, String arn, Response successResponse) {
        TagHandler handler = resolveHandler(arn);
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

    private TagHandler resolveHandler(String arn) {
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
