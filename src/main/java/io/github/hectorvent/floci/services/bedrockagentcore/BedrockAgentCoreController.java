package io.github.hectorvent.floci.services.bedrockagentcore;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

/**
 * Amazon Bedrock AgentCore data-plane stub ({@code InvokeAgentRuntime}).
 *
 * <p>Real endpoint: {@code POST /runtimes/{agentRuntimeArn}/invocations}. The ARN is
 * URL-encoded in the path, so the template regex captures the remaining path segments.
 * The payload (opaque binary, up to 100MB) is never parsed. Returns a fixed canned
 * body and echoes the runtime session id. Streaming is not emulated.
 */
@Path("/")
public class BedrockAgentCoreController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreController.class);
    private static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";

    private final BedrockAgentCoreService service;

    @Inject
    public BedrockAgentCoreController(BedrockAgentCoreService service) {
        this.service = service;
    }

    @POST
    @Path("/runtimes/{agentRuntimeArn:.+}/invocations")
    @Consumes(MediaType.WILDCARD)
    @Produces(MediaType.APPLICATION_JSON)
    public Response invokeAgentRuntime(@Context HttpHeaders headers,
                                       @PathParam("agentRuntimeArn") String agentRuntimeArn,
                                       @QueryParam("qualifier") String qualifier,
                                       @QueryParam("accountId") String accountId,
                                       byte[] payload) {
        String sessionId = headers.getHeaderString(SESSION_HEADER);
        LOG.debugv("InvokeAgentRuntime: arn={0}, qualifier={1}, bytes={2}",
                agentRuntimeArn, qualifier, payload == null ? 0 : payload.length);

        Response.ResponseBuilder builder = Response.ok(service.invoke(), MediaType.APPLICATION_JSON);
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        return builder.build();
    }
}
