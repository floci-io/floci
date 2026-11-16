package io.floci.aws.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.iot.model.IotV2LoggingOptions;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v2LoggingOptions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IotLoggingController {

    private final IotLoggingService loggingService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public IotLoggingController(IotLoggingService loggingService, RegionResolver regionResolver,
                                ObjectMapper objectMapper) {
        this.loggingService = loggingService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @POST
    public Response setV2LoggingOptions(@Context HttpHeaders headers, String body) {
        loggingService.setV2LoggingOptions(IotRequestBody.read(objectMapper, body),
                regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    @GET
    public Response getV2LoggingOptions(@Context HttpHeaders headers) {
        IotV2LoggingOptions options = loggingService.getV2LoggingOptions(regionResolver.resolveRegion(headers));
        ObjectNode response = objectMapper.createObjectNode();
        if (options.roleArn() != null) {
            response.put("roleArn", options.roleArn());
        }
        if (options.defaultLogLevel() != null) {
            response.put("defaultLogLevel", options.defaultLogLevel());
        }
        response.put("disableAllLogs", options.disableAllLogs());
        return Response.ok(response).build();
    }
}
