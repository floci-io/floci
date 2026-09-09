package io.github.hectorvent.floci.services.lakeformation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.lakeformation.model.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LakeFormationController {

    private final LakeFormationService service;
    private final ObjectMapper mapper;
    private final RegionResolver regionResolver;

    @Inject
    public LakeFormationController(LakeFormationService service, ObjectMapper mapper, RegionResolver regionResolver) {
        this.service = service;
        this.mapper = mapper;
        this.regionResolver = regionResolver;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            JsonNode request = mapper.readTree(body);
            if (request == null || !request.isObject()) {
                throw new AwsException("SerializationException", "Request body must be a JSON object.", 400);
            }
            return request;
        } catch (AwsException e) {
            throw e;
        } catch (Exception e) {
            throw new AwsException("SerializationException", "Request body is not valid JSON.", 400);
        }
    }

    private Response handleResponse(Object responseModel) {
        if (responseModel == null) {
            return Response.ok()
                    .header("x-amzn-RequestId", "floci-" + System.currentTimeMillis())
                    .build();
        }
        return Response.ok()
                .header("x-amzn-RequestId", "floci-" + System.currentTimeMillis())
                .entity(responseModel)
                .build();
    }

    @POST
    @Path("/PutDataLakeSettings")
    public Response putDataLakeSettings(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.putDataLakeSettings(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), PutDataLakeSettingsRequest.class)));
    }

    @POST
    @Path("/GetDataLakeSettings")
    public Response getDataLakeSettings(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.getDataLakeSettings(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), GetDataLakeSettingsRequest.class)));
    }

    @POST
    @Path("/RegisterResource")
    public Response registerResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.registerResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), RegisterResourceRequest.class)));
    }

    @POST
    @Path("/UpdateResource")
    public Response updateResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.updateResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), UpdateResourceRequest.class)));
    }

    @POST
    @Path("/DeregisterResource")
    public Response deregisterResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.deregisterResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), DeregisterResourceRequest.class)));
    }

    @POST
    @Path("/ListResources")
    public Response listResources(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.listResources(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), ListResourcesRequest.class)));
    }

    @POST
    @Path("/DescribeResource")
    public Response describeResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.describeResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), DescribeResourceRequest.class)));
    }

    @POST
    @Path("/GrantPermissions")
    public Response grantPermissions(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.grantPermissions(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), GrantPermissionsRequest.class)));
    }

    @POST
    @Path("/RevokePermissions")
    public Response revokePermissions(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.revokePermissions(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), RevokePermissionsRequest.class)));
    }

    @POST
    @Path("/ListPermissions")
    public Response listPermissions(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.listPermissions(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), ListPermissionsRequest.class)));
    }

    @POST
    @Path("/CreateLFTag")
    public Response createLFTag(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.createLFTag(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), CreateLFTagRequest.class)));
    }

    @POST
    @Path("/GetLFTag")
    public Response getLFTag(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.getLFTag(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), GetLFTagRequest.class)));
    }

    @POST
    @Path("/UpdateLFTag")
    public Response updateLFTag(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.updateLFTag(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), UpdateLFTagRequest.class)));
    }

    @POST
    @Path("/DeleteLFTag")
    public Response deleteLFTag(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.deleteLFTag(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), DeleteLFTagRequest.class)));
    }

    @POST
    @Path("/ListLFTags")
    public Response listLFTags(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.listLFTags(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), ListLFTagsRequest.class)));
    }

    @POST
    @Path("/AddLFTagsToResource")
    public Response addLFTagsToResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.addLFTagsToResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), AddLFTagsToResourceRequest.class)));
    }

    @POST
    @Path("/RemoveLFTagsFromResource")
    public Response removeLFTagsFromResource(@Context HttpHeaders headers, String body) throws Exception {
        return handleResponse(service.removeLFTagsFromResource(regionResolver.resolveRegion(headers), mapper.treeToValue(parse(body), RemoveLFTagsFromResourceRequest.class)));
    }
}
