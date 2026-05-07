package io.github.hectorvent.floci.services.ssm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsErrorResponse;
import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Map;

/**
 * Handles the ec2messages internal protocol used by the amazon-ssm-agent.
 *
 * Operations (X-Amz-Target: AmazonSSMMessageDeliveryService.*):
 * - GetMessages        — agent polls for pending commands
 * - AcknowledgeMessage — agent confirms receipt
 * - SendReply          — agent sends command output
 * - FailMessage        — agent reports processing failure
 * - DeleteMessage      — agent discards a message
 * - GetEndpoint        — agent discovers the service endpoint
 */
@ApplicationScoped
public class Ec2MessagesJsonHandler {

    private static final Logger LOG = Logger.getLogger(Ec2MessagesJsonHandler.class);

    private final SsmCommandService commandService;
    private final ObjectMapper objectMapper;
    private final EmulatorConfig config;

    @Inject
    public Ec2MessagesJsonHandler(SsmCommandService commandService, ObjectMapper objectMapper, EmulatorConfig config) {
        this.commandService = commandService;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    public Response handle(String action, JsonNode request, String region) {
        return switch (action) {
            case "GetMessages" -> handleGetMessages(request, region);
            case "AcknowledgeMessage" -> handleAcknowledgeMessage(request);
            case "SendReply" -> handleSendReply(request);
            case "FailMessage" -> handleFailMessage(request);
            case "DeleteMessage" -> handleDeleteMessage(request);
            case "GetEndpoint" -> handleGetEndpoint(request, region);
            default -> Response.status(400)
                    .entity(new AwsErrorResponse("UnsupportedOperation", "Operation " + action + " is not supported."))
                    .build();
        };
    }

    private Response handleGetMessages(JsonNode request, String region) {
        String destination = request.path("Destination").asText();
        String messagesRequestId = request.path("MessagesRequestId").asText("");
        int visibilityTimeout = request.path("VisibilityTimeoutInSeconds").asInt(30);

        List<Map<String, Object>> messages = commandService.getMessages(destination, messagesRequestId, visibilityTimeout);

        ObjectNode response = objectMapper.createObjectNode();
        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (Map<String, Object> msg : messages) {
            ObjectNode msgNode = objectMapper.createObjectNode();
            msg.forEach((k, v) -> msgNode.put(k, v.toString()));
            messagesArray.add(msgNode);
        }
        response.set("Messages", messagesArray);
        return Response.ok(response).build();
    }

    private Response handleAcknowledgeMessage(JsonNode request) {
        String messageId = request.path("MessageId").asText();
        commandService.acknowledgeMessage(messageId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleSendReply(JsonNode request) {
        String messageId = request.path("MessageId").asText();
        String payload = request.path("Payload").asText();
        commandService.sendReply(messageId, payload);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleFailMessage(JsonNode request) {
        String messageId = request.path("MessageId").asText();
        String failureType = request.path("FailureType").asText("InternalError");
        commandService.failMessage(messageId, failureType);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleDeleteMessage(JsonNode request) {
        String messageId = request.path("MessageId").asText();
        commandService.deleteMessage(messageId);
        return Response.ok(objectMapper.createObjectNode()).build();
    }

    private Response handleGetEndpoint(JsonNode request, String region) {
        String protocol = request.path("Protocol").asText("ec2messages");
        String endpoint = config.effectiveBaseUrl();

        ObjectNode endpointNode = objectMapper.createObjectNode();
        endpointNode.put("Protocol", protocol);
        endpointNode.put("Endpoint", endpoint);

        ObjectNode response = objectMapper.createObjectNode();
        response.set("Endpoint", endpointNode);
        return Response.ok(response).build();
    }
}
