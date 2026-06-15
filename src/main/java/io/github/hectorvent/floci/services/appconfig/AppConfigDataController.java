package io.github.hectorvent.floci.services.appconfig;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.services.appconfig.AppConfigDataService.ConfigurationData;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AppConfigDataController {
    private static final Logger LOG = Logger.getLogger(AppConfigDataController.class);

    private final AppConfigDataService service;
    private final ObjectMapper objectMapper;

    @Inject
    public AppConfigDataController(AppConfigDataService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Path("/configurationsessions")
    public Response startConfigurationSession(String body) throws IOException {
        @SuppressWarnings("unchecked")
        Map<String, Object> request = objectMapper.readValue(body, Map.class);
        String token = service.startConfigurationSession(request);
        return Response.status(201).entity(Map.of("InitialConfigurationToken", token)).build();
    }

    // Bound to an internal path rather than the public "/configuration" so that
    // an S3 bucket named "configuration" is not shadowed by this route (issue
    // #1294). AppConfigConfigurationRouteFilter rewrites genuine GetLatestConfiguration
    // requests (those carrying a configuration_token) onto this path; requests
    // without a token fall through to S3's /{bucket} handler.
    @GET
    @Path(AppConfigConfigurationRouteFilter.INTERNAL_PATH)
    public Response getLatestConfiguration(@QueryParam("configuration_token") String token) {
        ConfigurationData data = service.getLatestConfiguration(token);
        return Response.ok(data.content())
                .header("Content-Type", data.contentType())
                .header("Version-Label", data.configurationVersion())
                .header("Next-Poll-Configuration-Token", data.nextPollConfigurationToken())
                .header("Next-Poll-Interval-In-Seconds", 15)
                .build();
    }
}
