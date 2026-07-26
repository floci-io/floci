package io.github.hectorvent.floci.services.sagemaker;

import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.sagemaker.model.SageMakerEntities.EndpointResource;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

@Path("/endpoints")
public class SageMakerRuntimeController {
    private static final Logger LOG = Logger.getLogger(SageMakerRuntimeController.class);

    private final SageMakerService service;
    private final RegionResolver regionResolver;
    private final HttpClient httpClient;

    @Inject
    public SageMakerRuntimeController(SageMakerService service, RegionResolver regionResolver) {
        this.service = service;
        this.regionResolver = regionResolver;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @POST
    @Path("/{endpointName}/invocations")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.WILDCARD)
    public Response invoke(@PathParam("endpointName") String endpointName,
                           @HeaderParam("Content-Type") String contentType,
                           @HeaderParam("Accept") String accept,
                           @Context HttpHeaders headers,
                           byte[] body) {
        EndpointResource ep = service.endpoint(endpointName).orElse(null);
        if (ep == null) {
            return validation("Endpoint " + endpointName + " of account " + regionResolver.getAccountId() + " not found.");
        }
        if (!"InService".equals(ep.endpointStatus) || ep.invokeHost == null) {
            return validation("Endpoint " + endpointName + " is not InService.");
        }
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://" + ep.invokeHost + ":" + ep.invokePort + "/invocations"))
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body == null ? new byte[0] : body));
            if (contentType != null) {
                builder.header("Content-Type", contentType);
            }
            if (accept != null) {
                builder.header("Accept", accept);
            }
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            // SageMaker Runtime maps model-container 5xx responses to ModelError (HTTP 424).
            int status = response.statusCode() >= 500 ? 424 : response.statusCode();
            Response.ResponseBuilder out = Response.status(status).entity(response.body());
            response.headers().firstValue("Content-Type").ifPresent(out::type);
            return out.build();
        } catch (Exception e) {
            LOG.warnv("SageMaker endpoint invocation failed for {0}: {1}", endpointName, e.getMessage());
            return Response.status(424).entity(Map.of("__type", "ModelError", "message", e.getMessage())).build();
        }
    }

    private Response validation(String message) {
        return Response.status(400).entity(Map.of("__type", "ValidationError", "message", message)).build();
    }
}
