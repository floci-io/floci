package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Receives the response a CloudFormation custom-resource Lambda PUTs to its {@code ResponseURL}.
 *
 * <p>Stands in for the pre-signed S3 URL that real CloudFormation hands out. Floci points each
 * custom resource's {@code ResponseURL} at {@code <floci>/cfn-response/{token}}; the backing Lambda
 * PUTs its result document here, and {@link CustomResourceResponseStore} hands it to the waiting
 * provisioner.
 *
 * <p>The literal {@code /cfn-response/...} path takes precedence over the S3 controller's
 * {@code /{bucket}} template in JAX-RS matching, so this never collides with path-style S3.
 */
@Path("/cfn-response")
public class CfnResponseController {

    private static final Logger LOG = Logger.getLogger(CfnResponseController.class);

    private final CustomResourceResponseStore store;
    private final ObjectMapper objectMapper;

    @Inject
    public CfnResponseController(CustomResourceResponseStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @PUT
    @Path("/{token}")
    public Response receive(@PathParam("token") String token, String body) {
        try {
            store.complete(token, objectMapper.readTree(body));
        } catch (Exception e) {
            LOG.warnv("Failed to parse custom-resource response for token {0}: {1}", token, e.getMessage());
            store.complete(token, objectMapper.createObjectNode().put("Status", "FAILED")
                    .put("Reason", "Floci could not parse the custom-resource response body"));
        }
        // S3 pre-signed PUT returns 200 with an empty body; cfn-response only checks the status.
        return Response.ok().build();
    }
}
