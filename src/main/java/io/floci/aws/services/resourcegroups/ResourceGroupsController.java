package io.floci.aws.services.resourcegroups;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.RequestContext;
import io.floci.aws.services.resourcegroups.model.ResourceGroup;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path(ResourceGroupsRouteFilter.INTERNAL_PREFIX)
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ResourceGroupsController {
    private final ResourceGroupsService service;
    private final RegionResolver regions;
    private final RequestContext context;
    private final ObjectMapper mapper;

    @Inject
    public ResourceGroupsController(ResourceGroupsService service, RegionResolver regions,
                                    RequestContext context, ObjectMapper mapper) {
        this.service = service;
        this.regions = regions;
        this.context = context;
        this.mapper = mapper;
    }

    @POST
    @Path("/groups")
    public Response createGroup(@Context HttpHeaders headers, String body) {
        ResourceGroup group = service.create(regions.resolveRegion(headers), context.getAccountId(), parse(body));
        return Response.ok(Map.of("Group", groupMetadata(group), "Tags", group.tags(),
                "GroupConfiguration", Map.of("Configuration", group.configuration(), "Status", "UPDATE_COMPLETE"))).build();
    }

    @POST
    @Path("/get-group")
    public Response getGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(Map.of("Group", groupMetadata(
                service.get(regions.resolveRegion(headers), context.getAccountId(), parse(body))))).build();
    }

    @POST
    @Path("/delete-group")
    public Response deleteGroup(@Context HttpHeaders headers, String body) {
        return Response.ok(Map.of("Group", groupMetadata(
                service.delete(regions.resolveRegion(headers), context.getAccountId(), parse(body))))).build();
    }

    @POST
    @Path("/group-resources")
    public Response groupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.add(regions.resolveRegion(headers), context.getAccountId(), parse(body))).build();
    }

    @POST
    @Path("/list-group-resources")
    public Response listGroupResources(@Context HttpHeaders headers, String body) {
        return Response.ok(service.list(regions.resolveRegion(headers), context.getAccountId(), parse(body))).build();
    }

    private Map<String, Object> groupMetadata(ResourceGroup group) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("GroupArn", group.arn());
        result.put("Name", group.name());
        if (group.description() != null) {
            result.put("Description", group.description());
        }
        return result;
    }

    private JsonNode parse(String body) {
        try {
            return mapper.readTree(body == null ? "{}" : body);
        } catch (JsonProcessingException error) {
            throw new AwsException("BadRequestException", "The request must contain a JSON object.", 400);
        }
    }
}
