package io.github.hectorvent.floci.services.sns;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.sns.model.PushNotification;
import io.github.hectorvent.floci.services.sqs.model.MessageAttributeValue;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

/**
 * Floci-specific inspection endpoint for captured mobile push notifications.
 * SNS push delivery is mocked — APNS and FCM are never contacted, so tests need
 * a way to assert what would have been sent. This controller exposes that capture.
 */
@Path("/_aws/sns/push-notifications")
@Produces(MediaType.APPLICATION_JSON)
public class SnsPushInspectionController {

    private final SnsService snsService;
    private final ObjectMapper objectMapper;

    @Inject
    public SnsPushInspectionController(SnsService snsService, ObjectMapper objectMapper) {
        this.snsService = snsService;
        this.objectMapper = objectMapper;
    }

    @GET
    public Response getPushNotifications(@QueryParam("EndpointArn") String endpointArn) {
        List<PushNotification> captured = snsService.peekPushNotifications(endpointArn);
        ArrayNode arr = objectMapper.createArrayNode();
        for (PushNotification p : captured) {
            ObjectNode node = objectMapper.createObjectNode();
            node.put("MessageId", p.messageId());
            node.put("EndpointArn", p.endpointArn());
            node.put("PlatformApplicationArn", p.platformApplicationArn());
            node.put("Platform", p.platform());
            node.put("Token", p.token());
            node.put("Payload", p.payload());
            if (p.subject() != null) {
                node.put("Subject", p.subject());
            } else {
                node.putNull("Subject");
            }
            node.put("Timestamp", p.timestamp().toString());
            ObjectNode attrs = node.putObject("MessageAttributes");
            if (p.messageAttributes() != null) {
                for (Map.Entry<String, MessageAttributeValue> entry : p.messageAttributes().entrySet()) {
                    ObjectNode attr = attrs.putObject(entry.getKey());
                    attr.put("DataType", entry.getValue().getDataType());
                    if (entry.getValue().getStringValue() != null) {
                        attr.put("StringValue", entry.getValue().getStringValue());
                    }
                    if (entry.getValue().getBinaryValue() != null) {
                        attr.put("BinaryValue", entry.getValue().getBinaryValue());
                    }
                }
            }
            arr.add(node);
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("notifications", arr);
        return Response.ok(result).build();
    }

    @DELETE
    public Response clearPushNotifications() {
        snsService.clearPushNotifications();
        return Response.ok().build();
    }
}
