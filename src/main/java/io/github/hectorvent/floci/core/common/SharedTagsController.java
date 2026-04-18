package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Dispatcher for AWS services that share the REST {@code /tags/{resourceArn}} path
 * (API Gateway, EventBridge Scheduler, EFS, ...).
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
    @Path("/{arn: .*}")
    public Response listTags(@Context HttpHeaders headers, @PathParam("arn") String arn) {
        TagHandler handler = resolveHandler(arn);
        String region = regionResolver.resolveRegion(headers);
        Map<String, String> tags = handler.listTags(region, arn);
        ObjectNode root = objectMapper.createObjectNode();
        ObjectNode tagsNode = root.putObject("tags");
        tags.forEach(tagsNode::put);
        return Response.ok(root).build();
    }

    @POST
    @Path("/{arn: .*}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResourcePost(@Context HttpHeaders headers,
                                    @PathParam("arn") String arn,
                                    String body) {
        return tagResource(headers, arn, body);
    }

    @PUT
    @Path("/{arn: .*}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response tagResource(@Context HttpHeaders headers,
                                @PathParam("arn") String arn,
                                String body) {
        TagHandler handler = resolveHandler(arn);
        String region = regionResolver.resolveRegion(headers);
        try {
            JsonNode node = objectMapper.readTree(body);
            Map<String, String> tags = new HashMap<>();
            node.path("tags").fields().forEachRemaining(e -> tags.put(e.getKey(), e.getValue().asText()));
            handler.tagResource(region, arn, tags);
            return Response.noContent().build();
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("BadRequestException", e.getMessage(), 400);
        }
    }

    @DELETE
    @Path("/{arn: .*}")
    public Response untagResource(@Context HttpHeaders headers,
                                  @PathParam("arn") String arn,
                                  @QueryParam("tagKeys") List<String> tagKeys) {
        TagHandler handler = resolveHandler(arn);
        String region = regionResolver.resolveRegion(headers);
        handler.untagResource(region, arn, tagKeys);
        return Response.noContent().build();
    }

    private TagHandler resolveHandler(String arn) {
        // arn:aws:<service>:<region>:<account>:<resource>
        String[] parts = arn.split(":", 6);
        if (parts.length < 6 || !"arn".equals(parts[0])) {
            throw new AwsException("BadRequestException",
                    "Invalid resource ARN: " + arn, 400);
        }
        String serviceKey = parts[2];
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
