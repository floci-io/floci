package io.github.hectorvent.floci.services.controlcatalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsRegions;
import io.github.hectorvent.floci.core.common.JsonErrorResponseUtils;
import io.github.hectorvent.floci.core.common.RegionResolver;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ControlCatalogController {
    private final ControlCatalogService controlCatalogService;
    private final ObjectMapper objectMapper;
    private final RegionResolver regionResolver;

    @Inject
    public ControlCatalogController(ControlCatalogService controlCatalogService,
                                    ObjectMapper objectMapper,
                                    RegionResolver regionResolver) {
        this.controlCatalogService = controlCatalogService;
        this.objectMapper = objectMapper;
        this.regionResolver = regionResolver;
    }

    @POST
    @Path("/get-control")
    public Response getControl(String body) {
        JsonNode request = readTree(body);
        return Response.ok(controlCatalogService.getControl(request, regionResolver.getRegion())).build();
    }

    @POST
    @Path("/list-controls")
    public Response listControls(@QueryParam("maxResults") String maxResults,
                                 @QueryParam("nextToken") String nextToken,
                                 String body) {
        return Response.ok(controlCatalogService.listControls(readTree(body), maxResults, nextToken,
                AwsRegions.partitionFor(regionResolver.getRegion()))).build();
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (Exception e) {
            throw new WebApplicationException(JsonErrorResponseUtils.createSerializationErrorResponse());
        }
    }
}
