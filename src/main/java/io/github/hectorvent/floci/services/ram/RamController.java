package io.github.hectorvent.floci.services.ram;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ram.model.PrincipalAssociation;
import io.github.hectorvent.floci.services.ram.model.ResourceShare;
import io.github.hectorvent.floci.services.ram.model.SharedResource;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ObjectReader requestReader;
    private final RegionResolver regionResolver;

    @Inject
    public RamController(RamService service, ObjectMapper objectMapper, RegionResolver regionResolver) {
        this.service = service;
        this.objectMapper = objectMapper;
        this.requestReader = objectMapper.reader()
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
        String resourceOwner = request.hasNonNull("resourceOwner")
                ? request.path("resourceOwner").asText() : null;
        List<ResourceShare> shares = service.getResourceShares(
                regionResolver.getAccountId(),
                resourceOwner,
                request.hasNonNull("name") ? request.path("name").asText() : null,
                stringList(request.path("resourceShareArns")),
                request.hasNonNull("resourceShareStatus")
                        ? request.path("resourceShareStatus").asText() : null);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = objectMapper.createArrayNode();
        shares.forEach(share -> array.add(shareNode(share)));
        response.set("resourceShares", array);
        return Response.ok(response).build();
    }

    @DELETE
    @Path("/deleteresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response deleteResourceShare(@QueryParam("resourceShareArn") String resourceShareArn,
                                        @QueryParam("clientToken") String clientToken) {
        service.deleteResourceShare(resourceShareArn, regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.put("returnValue", true);
        // The response models clientToken; AWS echoes what the caller sent so a retry can be
        // correlated with the original request.
        if (clientToken != null) {
            response.put("clientToken", clientToken);
        }
        return Response.ok(response).build();
    }

    @POST
    @Path("/updateresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response updateResourceShare(String body) {
        JsonNode request = readTree(body);
        ResourceShare updated = service.updateResourceShare(
                request.path("resourceShareArn").asText(),
                request.hasNonNull("name") ? request.path("name").asText() : null,
                request.hasNonNull("allowExternalPrincipals")
                        ? request.path("allowExternalPrincipals").asBoolean() : null,
                regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShare", shareNode(updated));
        return Response.ok(response).build();
    }

    @POST
    @Path("/associateresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response associateResourceShare(String body) {
        JsonNode request = readTree(body);
        String resourceShareArn = request.path("resourceShareArn").asText();
        List<String> resourceArns = stringList(request.path("resourceArns"));
        List<String> principals = stringList(request.path("principals"));
        ResourceShare updated = service.associateResourceShare(
                resourceShareArn, resourceArns, principals, regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShareAssociations",
                associationArray(updated, resourceArns, principals, "ASSOCIATED"));
        return Response.ok(response).build();
    }

    @POST
    @Path("/disassociateresourceshare")
    @Consumes(MediaType.WILDCARD)
    public Response disassociateResourceShare(String body) {
        JsonNode request = readTree(body);
        String resourceShareArn = request.path("resourceShareArn").asText();
        List<String> resourceArns = stringList(request.path("resourceArns"));
        List<String> principals = stringList(request.path("principals"));
        ResourceShare updated = service.disassociateResourceShare(
                resourceShareArn, resourceArns, principals, regionResolver.getAccountId());

        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShareAssociations",
                associationArray(updated, resourceArns, principals, "DISASSOCIATED"));
        return Response.ok(response).build();
    }

    @POST
    @Path("/listprincipals")
    @Consumes(MediaType.WILDCARD)
    public Response listPrincipals(String body) {
        JsonNode request = readTree(body);
        String resourceOwner = request.hasNonNull("resourceOwner")
                ? request.path("resourceOwner").asText() : null;
        List<PrincipalAssociation> principals = service.listPrincipals(
                regionResolver.getAccountId(), resourceOwner, stringList(request.path("resourceShareArns")));

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode array = objectMapper.createArrayNode();
        for (PrincipalAssociation principal : principals) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("id", principal.id());
            node.put("resourceShareArn", principal.resourceShareArn());
            node.put("creationTime", principal.creationTime().toEpochMilli() / 1000.0);
            node.put("lastUpdatedTime", principal.lastUpdatedTime().toEpochMilli() / 1000.0);
            node.put("external", principal.external());
            array.add(node);
        }
        response.set("principals", array);
        return Response.ok(response).build();
    }

    @POST
    @Path("/tagresource")
    @Consumes(MediaType.WILDCARD)
    public Response tagResource(String body) {
        JsonNode request = readTree(body);
        Map<String, String> tags = new LinkedHashMap<>();
        request.path("tags").forEach(tag -> tags.put(tag.path("key").asText(), tag.path("value").asText()));
        service.tagResource(request.path("resourceShareArn").asText(), tags,
                regionResolver.getAccountId());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/untagresource")
    @Consumes(MediaType.WILDCARD)
    public Response untagResource(String body) {
        JsonNode request = readTree(body);
        service.untagResource(request.path("resourceShareArn").asText(),
                stringList(request.path("tagKeys")), regionResolver.getAccountId());
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @POST
    @Path("/getresourceshareinvitations")
    @Consumes(MediaType.WILDCARD)
    public Response getResourceShareInvitations(String body) {
        // The body is semantically ignored (invitations are always empty under
        // organization sharing) but malformed JSON is still a client error.
        readTree(body);
        ObjectNode response = objectMapper.createObjectNode();
        response.set("resourceShareInvitations", objectMapper.createArrayNode());
        return Response.ok(response).build();
    }

    @POST
    @Path("/listresources")
    @Consumes(MediaType.WILDCARD)
    public Response listResources(String body) {
        JsonNode request = readTree(body);
        String resourceOwner = request.hasNonNull("resourceOwner")
                ? request.path("resourceOwner").asText() : null;
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
        // Shares created through the API are STANDARD; CREATED_FROM_POLICY covers shares RAM
        // derives from a resource policy, which floci has no path to produce.
        node.put("featureSet", "STANDARD");
        node.put("creationTime", share.getCreationTime().toEpochMilli() / 1000.0);
        node.put("lastUpdatedTime", share.getLastUpdatedTime().toEpochMilli() / 1000.0);
        ArrayNode tags = objectMapper.createArrayNode();
        share.getTags().forEach((key, value) -> {
            ObjectNode tag = objectMapper.createObjectNode();
            tag.put("key", key);
            tag.put("value", value);
            tags.add(tag);
        });
        node.set("tags", tags);
        return node;
    }

    /** One row per associated/disassociated resourceArn and principal, per the real RAM shape. */
    private ArrayNode associationArray(ResourceShare share, List<String> resourceArns,
                                       List<String> principals, String status) {
        ArrayNode array = objectMapper.createArrayNode();
        double now = share.getLastUpdatedTime().toEpochMilli() / 1000.0;
        for (String resourceArn : resourceArns) {
            array.add(associationNode(share, resourceArn, "RESOURCE", status, now));
        }
        for (String principal : principals) {
            array.add(associationNode(share, principal, "PRINCIPAL", status, now));
        }
        return array;
    }

    private ObjectNode associationNode(ResourceShare share, String associatedEntity,
                                       String associationType, String status, double time) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("resourceShareArn", share.getResourceShareArn());
        node.put("resourceShareName", share.getName());
        node.put("associatedEntity", associatedEntity);
        node.put("associationType", associationType);
        node.put("status", status);
        node.put("creationTime", time);
        node.put("lastUpdatedTime", time);
        node.put("external", false);
        return node;
    }

    /**
     * A body that is not valid JSON is a client error: restJson1 rejects it with 400
     * SerializationException. Left as an UncheckedIOException it escapes to Quarkus'
     * generic handler and the SDK sees a 500 InternalFailure instead.
     */
    private JsonNode readTree(String body) {
        try {
            return (body == null || body.isBlank())
                    ? objectMapper.createObjectNode()
                    // Strict: Jackson's default stops at the first complete value, so
                    // "{} not-json" would parse as an empty object and the request would run.
                    : requestReader.readTree(body);
        } catch (IOException e) {
            throw new AwsException("SerializationException",
                    "The request could not be parsed as valid JSON.", 400);
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        node.forEach(n -> values.add(n.asText()));
        return values;
    }
}
