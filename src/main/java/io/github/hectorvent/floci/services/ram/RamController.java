package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * AWS Resource Access Manager (Smithy restJson1).
 *
 * <p>Serves the RAM calls AWS Landing Zone Accelerator makes: the organization-sharing opt-in
 * plus the share reads of the Custom::GetResourceShare / Custom::GetResourceShareItem Lambdas.
 * The literal paths take JAX-RS precedence over S3's {@code /{bucket}} template route, so no
 * extra routing wiring is needed — but any RAM path missing here falls through to S3 and
 * produces an XML error a restJson1 client cannot parse.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RamController {

    private final RamService service;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public RamController(RamService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/enablesharingwithawsorganization")
    @Consumes(MediaType.WILDCARD)
    public Response enableSharingWithAwsOrganization() {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", service.enableSharingWithAwsOrganization());
        return Response.ok(response).build();
    }

    @POST
    @Path("/createresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response createResourceShare(@Context HttpHeaders headers, String body) {
        JsonNode request = readTree(body);
        String name = request.path("name").asText();
        boolean allowExternalPrincipals = request.path("allowExternalPrincipals").asBoolean(false);
        ResourceShare share = service.createResourceShare(
                name,
                stringList(request.path("principals")),
                stringList(request.path("resourceArns")),
                allowExternalPrincipals,
                regionResolver.resolveRegion(headers),
                regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShare", shareNode(share));
        return Response.ok(response).build();
    }

    @POST
    @Path("/getresourceshares")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShares(String body) {
        JsonNode request = readTree(body);
        String resourceOwner = request.path("resourceOwner").asText("SELF");
        List<ResourceShare> shares = service.getResourceShares(regionResolver.getAccountId(), resourceOwner);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = objectMapper.createArrayNode();
        shares.forEach(share -> array.add(shareNode(share)));
        response.set("resourceShares", array);
        return Response.ok(response).build();
    }

    @POST
    @Path("/getresourceshareinvitations")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShareInvitations(String body) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShareInvitations", objectMapper.createArrayNode());
        return Response.ok(response).build();
    }

    @POST
    @Path("/listresources")
    @Consumes(MediaType.WILDCARD)
    public Response listResources(String body) {
        JsonNode request = readTree(body);
        String resourceOwner = request.path("resourceOwner").asText("SELF");
        List<SharedResource> resources = service.listResources(
                regionResolver.getAccountId(), resourceOwner, stringList(request.path("resourceShareArns")));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = objectMapper.createArrayNode();
        for (SharedResource resource : resources) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("arn", resource.arn());
            node.put("type", resource.type());
            node.put("resourceShareArn", resource.resourceShareArn());
            node.put("status", resource.status());
            array.add(node);
        }
        response.set("resources", array);
        return Response.ok(response).build();
    }

    private ObjectNode shareNode(ResourceShare share) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceShareArn", share.getResourceShareArn());
        node.put("name", share.getName());
        node.put("owningAccountId", share.getOwningAccountId());
        node.put("allowExternalPrincipals", share.isAllowExternalPrincipals());
        node.put("status", share.getStatus());
        node.put("creationTime", share.getCreationTime().toEpochMilli() / 1000.0);
        node.put("lastUpdatedTime", share.getCreationTime().toEpochMilli() / 1000.0);
        return node;
    }

    private JsonNode readTree(String body) {
        try {
            return (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asText()));
        return values;
    }
}
