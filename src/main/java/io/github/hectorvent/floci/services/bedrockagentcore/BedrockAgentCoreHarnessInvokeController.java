package io.github.hectorvent.floci.services.bedrockagentcore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.util.function.Consumer;

/**
 * {@code InvokeHarness}, the AgentCore data plane's streaming operation. Answers with an
 * {@code application/vnd.amazon.eventstream} response, the same framing ConverseStream uses, so an
 * SDK client's stream iterator works unchanged.
 *
 * <p>Rooted at {@code /harnesses/invoke} on its own: the control plane's harness controller claims
 * {@code /harnesses}, and a request is matched against one root resource class, the one with the
 * longest literal path. A class of its own keeps this operation behind the data plane's service flag
 * and free of the control plane's JSON-only {@code @Produces}.
 */
@Path("/harnesses/invoke")
public class BedrockAgentCoreHarnessInvokeController {

    private static final Logger LOG = Logger.getLogger(BedrockAgentCoreHarnessInvokeController.class);
    private static final String SESSION_HEADER = "X-Amzn-Bedrock-AgentCore-Runtime-Session-Id";

    private final BedrockAgentCoreHarnessService harnessService;
    private final ObjectMapper objectMapper;

    @Inject
    public BedrockAgentCoreHarnessInvokeController(BedrockAgentCoreHarnessService harnessService,
                                                   ObjectMapper objectMapper) {
        this.harnessService = harnessService;
        this.objectMapper = objectMapper;
    }

    /**
     * {@code harnessArn} arrives as a query parameter and {@code runtimeSessionId} as a header,
     * which is how the SDK binds them; only {@code messages}, {@code model} and {@code tools} are in
     * the body.
     *
     * <p>Request validation happens before the first frame: once the stream is committed the status
     * is already 200 and a problem could only be reported as an in-stream exception frame.
     */
    @POST
    @Blocking
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
