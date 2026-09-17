package io.github.hectorvent.floci.services.bedrockagentcore;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.bedrockagentcorecontrol.BedrockAgentCoreControlService;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.smallrye.common.annotation.Blocking;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.StreamingOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;
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
 *
 * <p>By default any ARN is accepted (permissive). When
 * {@code floci.services.bedrock-agent-core.validate-runtime-exists=true}, an unknown
 * runtime ARN returns {@code ResourceNotFoundException} (404).
 */
@Path("/")
public class BedrockAgentCoreController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreController.class);
    private static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";

    private final BedrockAgentCoreService service;
    private final BedrockAgentCoreHarnessService harnessService;
    private final BedrockAgentCoreControlService controlService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;
    private final boolean validateRuntimeExists;

    @Inject
    public BedrockAgentCoreController(BedrockAgentCoreService service,
                                      BedrockAgentCoreHarnessService harnessService,
                                      BedrockAgentCoreControlService controlService,
                                      RegionResolver regionResolver,
                                      ObjectMapper objectMapper,
                                      EmulatorConfig config) {
        this.service = service;
        this.harnessService = harnessService;
        this.controlService = controlService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
        this.validateRuntimeExists = config.services().bedrockAgentCore().validateRuntimeExists();
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
        String region = regionResolver.resolveRegion(headers);
        if (validateRuntimeExists && !controlService.runtimeArnExists(region, agentRuntimeArn)) {
            return Response.status(404)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Amzn-Errortype", "ResourceNotFoundException")
                    .entity(new AwsErrorResponse("ResourceNotFoundException",
                            "AgentCore runtime not found: " + agentRuntimeArn))
                    .build();
        }

        String sessionId = headers.getHeaderString(SESSION_HEADER);
        LOG.debugv("InvokeAgentRuntime: arn={0}, qualifier={1}, bytes={2}",
                agentRuntimeArn, qualifier, payload == null ? 0 : payload.length);

        Response.ResponseBuilder builder = Response.ok(service.invoke(), MediaType.APPLICATION_JSON);
        if (sessionId != null) {
            builder.header(SESSION_HEADER, sessionId);
        }
        return builder.build();
    }

    /**
     * {@code InvokeHarness}. Answers with an {@code application/vnd.amazon.eventstream} response,
     * the same framing ConverseStream uses, so an SDK client's stream iterator works unchanged.
     *
     * <p>{@code harnessArn} arrives as a query parameter and {@code runtimeSessionId} as a header,
     * which is how the SDK binds them; only {@code messages}, {@code model} and {@code tools} are in
     * the body.
     *
     * <p>Request validation happens before the first frame: once the stream is committed the status
     * is already 200 and a problem could only be reported as an in-stream exception frame.
     */
    @POST
    @Blocking
    @Path("/harnesses/invoke")
    @Consumes(MediaType.WILDCARD)
    public Response invokeHarness(@Context HttpHeaders headers,
                                 @QueryParam("harnessArn") String harnessArn,
                                 @QueryParam("qualifier") String harnessQualifier,
                                 String body) {
        ObjectNode request;
        try {
            JsonNode parsed = objectMapper.readTree(body != null && !body.isBlank() ? body : "{}");
            request = parsed.isObject() ? (ObjectNode) parsed : objectMapper.createObjectNode();
        } catch (IOException e) {
            throw new AwsException("ValidationException", "Request body is not valid JSON", 400);
        }

        // runtimeSessionId and runtimeUserId are headers on the wire, not body fields. Only the
        // session id is read: runtimeUserId reaches the harness on AWS but changes nothing here.
        String runtimeSessionId = headers.getHeaderString(SESSION_HEADER);
        Consumer<OutputStream> stream = harnessService.invokeHarness(harnessArn, runtimeSessionId, request);
        LOG.debugv("InvokeHarness: arn={0}, qualifier={1}, session={2}",
                harnessArn, harnessQualifier, runtimeSessionId);

        Response.ResponseBuilder builder = Response.ok(streaming(stream))
                .header("Content-Type", "application/vnd.amazon.eventstream");
        if (runtimeSessionId != null) {
            builder.header(SESSION_HEADER, runtimeSessionId);
        }
        return builder.build();
    }

    /**
     * The explicit return type is what lets {@code GenericEntity} keep {@code StreamingOutput} as
     * the entity type; inlining the constructor erases it to {@code Object}. Same helper as the
     * bedrock-runtime controller.
     */
    private static GenericEntity<StreamingOutput> streaming(Consumer<OutputStream> stream) {
        return new GenericEntity<>(stream::accept, StreamingOutput.class);
    }
}
