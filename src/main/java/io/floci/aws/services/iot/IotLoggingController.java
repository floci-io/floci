package io.floci.aws.services.iot;

import com.fasterxml.jackson.databind.ObjectMapper;
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
        return Response.ok().build();
    }

    @GET
    public Response getV2LoggingOptions(@Context HttpHeaders headers) {
        IotV2LoggingOptions options = loggingService.getV2LoggingOptions(regionResolver.resolveRegion(headers));
        return Response.ok(objectMapper.createObjectNode()
                .put("roleArn", options.roleArn())
                .put("defaultLogLevel", options.defaultLogLevel())
                .put("disableAllLogs", options.disableAllLogs())).build();
    }
}
