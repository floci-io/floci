package io.github.hectorvent.floci.services.bedrockruntime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * AWS Bedrock Runtime REST JSON endpoints.
 *
 * Real Bedrock Runtime uses {@code POST /model/{modelId}/converse} and
 * {@code POST /model/{modelId}/invoke} against the
 * {@code bedrock-runtime.<region>.amazonaws.com} host. Floci routes all
 * hostnames to port 4566, so path-based dispatch is sufficient.
 *
 * Returns dummy responses only: no real model inference.
 */
@Path("/model")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BedrockRuntimeController {

    private static final Logger LOG = Logger.getLogger(BedrockRuntimeController.class);

    private final BedrockRuntimeService service;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockRuntimeController(BedrockRuntimeService service, ObjectMapper objectMapper) {
        this.service = service;
        this.objectMapper = objectMapper;
    }

    @POST
    @Blocking
    @Path("/{modelId:.+}/converse")
    public Response converse(@PathParam("modelId") String modelId, String body) {
        if (modelId == null || modelId.isBlank()) {
            throw new AwsException("ValidationException", "modelId is required.", 400);
        }
        JsonNode request;
        try {
            request = body == null || body.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(body);
        } catch (Exception e) {
            throw new AwsException("ValidationException",
                    "Malformed request body: " + e.getMessage(), 400);
        }

        JsonNode messages = request.path("messages");
        if (!messages.isArray() || messages.isEmpty()) {
            throw new AwsException("ValidationException",
                    "messages is required and must be a non-empty array.", 400);
        }

        ObjectNode response = service.buildConverseResponse(modelId, (ObjectNode) request);
        LOG.debugv("Bedrock Converse: modelId={0}, messages={1}", modelId, messages.size());
        return Response.ok(response).build();
    }

    @POST
    @Blocking
    @Path("/{modelId:.+}/invoke")
    @Consumes(MediaType.WILDCARD)
    public Response invokeModel(@PathParam("modelId") String modelId, byte[] body) {
        if (modelId == null || modelId.isBlank()) {
            throw new AwsException("ValidationException", "modelId is required.", 400);
        }
        // Bedrock InvokeModel bodies are model-specific opaque blobs; do not parse.
        byte[] response = service.buildInvokeModelResponse(modelId, body);
        LOG.debugv("Bedrock InvokeModel: modelId={0}, bodyBytes={1}",
                modelId, body == null ? 0 : body.length);
        return Response.ok(response, MediaType.APPLICATION_JSON).build();
    }

    @POST
    @Path("/{modelId:.+}/invoke-with-response-stream")
    @Consumes(MediaType.WILDCARD)
    public Response invokeModelWithResponseStream(@PathParam("modelId") String modelId) {
        throw new AwsException("UnsupportedOperationException",
                "InvokeModelWithResponseStream is not supported by the Floci stub. "
                        + "Use InvokeModel or Converse instead.", 501);
    }

    @POST
    @Path("/{modelId:.+}/converse-stream")
    @Consumes(MediaType.WILDCARD)
    public Response converseStream(@PathParam("modelId") String modelId) {
        throw new AwsException("UnsupportedOperationException",
                "ConverseStream is not supported by the Floci stub. "
                        + "Use Converse instead.", 501);
    }
}
