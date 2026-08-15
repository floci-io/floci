package io.github.hectorvent.floci.services.lambda;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lambda.model.LambdaCapacityProvider;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * Lambda capacity provider endpoints — the /2025-11-30 API version prefix.
 *
 * CreateCapacityProvider: POST   /2025-11-30/capacity-providers
 * ListCapacityProviders:  GET    /2025-11-30/capacity-providers
 * GetCapacityProvider:    GET    /2025-11-30/capacity-providers/{CapacityProviderName}
 * UpdateCapacityProvider: PUT    /2025-11-30/capacity-providers/{CapacityProviderName}
 * DeleteCapacityProvider: DELETE /2025-11-30/capacity-providers/{CapacityProviderName}
 */
@Path("/2025-11-30")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LambdaCapacityProviderController {

    private final LambdaCapacityProviderService capacityProviderService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public LambdaCapacityProviderController(LambdaCapacityProviderService capacityProviderService,
                                            RegionResolver regionResolver,
                                            ObjectMapper objectMapper) {
        this.capacityProviderService = capacityProviderService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/capacity-providers")
    public Response createCapacityProvider(@Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        LambdaCapacityProvider provider = capacityProviderService.create(region, readTree(body));
        return Response.status(202).entity(wrap(provider)).build();
    }

    @GET
    @Path("/capacity-providers")
    public Response listCapacityProviders(@Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        List<LambdaCapacityProvider> found = capacityProviderService.list(region);
        ObjectNode root = objectMapper.createObjectNode();
        ArrayNode list = root.putArray("CapacityProviders");
        found.forEach(provider -> list.add(capacityProviderNode(provider)));
        return Response.ok(root).build();
    }

    @GET
    @Path("/capacity-providers/{capacityProviderName}")
    public Response getCapacityProvider(@PathParam("capacityProviderName") String name,
                                        @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.ok(wrap(capacityProviderService.get(region, name))).build();
    }

    @PUT
    @Path("/capacity-providers/{capacityProviderName}")
    public Response updateCapacityProvider(@PathParam("capacityProviderName") String name,
                                           @Context HttpHeaders headers, String body) {
        String region = regionResolver.resolveRegion(headers);
        LambdaCapacityProvider provider = capacityProviderService.update(region, name, readTree(body));
        return Response.status(202).entity(wrap(provider)).build();
    }

    @DELETE
    @Path("/capacity-providers/{capacityProviderName}")
    public Response deleteCapacityProvider(@PathParam("capacityProviderName") String name,
                                           @Context HttpHeaders headers) {
        String region = regionResolver.resolveRegion(headers);
        return Response.status(202).entity(wrap(capacityProviderService.delete(region, name))).build();
    }

    private ObjectNode wrap(LambdaCapacityProvider provider) {
        ObjectNode root = objectMapper.createObjectNode();
        root.set("CapacityProvider", capacityProviderNode(provider));
        return root;
    }

    private ObjectNode capacityProviderNode(LambdaCapacityProvider provider) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("CapacityProviderArn", provider.getArn());
        node.put("State", provider.getState());
        node.set("VpcConfig", provider.getVpcConfig());
        node.set("PermissionsConfig", provider.getPermissionsConfig());
        if (provider.getInstanceRequirements() != null) {
            node.set("InstanceRequirements", provider.getInstanceRequirements());
        }
        if (provider.getScalingConfig() != null) {
            node.set("CapacityProviderScalingConfig", provider.getScalingConfig());
        }
        if (provider.getKmsKeyArn() != null) {
            node.put("KmsKeyArn", provider.getKmsKeyArn());
        }
        node.put("LastModified", provider.getLastModified());
        return node;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new jakarta.ws.rs.WebApplicationException(
                    JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
