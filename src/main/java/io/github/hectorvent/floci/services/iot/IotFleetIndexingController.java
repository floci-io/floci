package io.github.hectorvent.floci.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.Field;
import io.github.hectorvent.floci.services.iot.model.IotIndexingConfiguration.ThingIndexing;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * REST-JSON routes for AWS IoT fleet indexing: UpdateIndexingConfiguration,
 * GetIndexingConfiguration and DescribeIndex, on the paths and shapes the AWS SDKs use.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotFleetIndexingController {

    private final IotFleetIndexingService fleetIndexingService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotFleetIndexingController(IotFleetIndexingService fleetIndexingService, RegionResolver regionResolver,
                                      ObjectMapper objectMapper) {
        this.fleetIndexingService = fleetIndexingService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/indexing/config")
    public Response updateIndexingConfiguration(@Context HttpHeaders headers, String body) {
        fleetIndexingService.updateIndexingConfiguration(IotRequestBody.read(objectMapper, body),
                regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    @Path("/indexing/config")
    public Response getIndexingConfiguration(@Context HttpHeaders headers) {
        IotIndexingConfiguration configuration =
                fleetIndexingService.getIndexingConfiguration(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        ThingIndexing thing = configuration.thing();
        ObjectNode thingNode = response.putObject("thingIndexingConfiguration");
        thingNode.put("thingIndexingMode", thing.thingIndexingMode());
        thingNode.put("thingConnectivityIndexingMode", thing.thingConnectivityIndexingMode());
        thingNode.put("deviceDefenderIndexingMode", thing.deviceDefenderIndexingMode());
        thingNode.put("namedShadowIndexingMode", thing.namedShadowIndexingMode());
        List<Field> managedFields = IotFleetIndexingService.thingManagedFields(thing);
        ObjectNode filter = thingNode.putObject("filter");
        // Managed fields exist exactly while thing indexing is on; AWS then reports the full filter too.
        if (!managedFields.isEmpty()) {
            thingNode.set("managedFields", objectMapper.valueToTree(managedFields));
            if (!thing.customFields().isEmpty()) {
                thingNode.set("customFields", objectMapper.valueToTree(thing.customFields()));
            }
            filter.set("namedShadowNames", objectMapper.valueToTree(thing.namedShadowNames()));
            filter.set("geoLocations", objectMapper.valueToTree(thing.geoLocations()));
            filter.putObject("connectivity")
                    .set("includeSocketInformation", objectMapper.valueToTree(thing.includeSocketInformation()));
        }
        ObjectNode groupNode = response.putObject("thingGroupIndexingConfiguration");
        groupNode.put("thingGroupIndexingMode", configuration.thingGroupIndexingMode());
        if ("ON".equals(configuration.thingGroupIndexingMode())) {
            groupNode.set("managedFields", objectMapper.valueToTree(IotFleetIndexingService.THING_GROUP_MANAGED_FIELDS));
        }
        return Response.ok(response).build();
    }

    @GET
    @Path("/indices/{indexName}")
    public Response describeIndex(@Context HttpHeaders headers, @PathParam("indexName") String indexName) {
        String schema = fleetIndexingService.describeIndex(indexName, regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        response.put("indexName", indexName);
        // ponytail: always ACTIVE, Floci indexes synchronously so there is no BUILDING or REBUILDING window.
        response.put("indexStatus", "ACTIVE");
        response.put("schema", schema);
        return Response.ok(response).build();
    }
}
