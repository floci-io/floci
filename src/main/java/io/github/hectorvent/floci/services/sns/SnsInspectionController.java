package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.services.sns.model.SentSms;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/**
 * LocalStack-compatible REST endpoint for inspecting published SNS SMS
 * messages. Mirrors {@code SesInspectionController} so test helpers can
 * retrieve verification codes sent via SMS.
 *
 * <p>GET  /_aws/sns           — all published SMS
 * <p>GET  /_aws/sns?phone=X   — filter by phone number (URL-encoded)
 * <p>GET  /_aws/sns?id=X      — filter by message ID
 * <p>DELETE /_aws/sns         — clear all stored SMS
 */
@Path("/_aws/sns")
@Produces(MediaType.APPLICATION_JSON)
public class SnsInspectionController {

    private final SnsService snsService;
    private final ObjectMapper objectMapper;

    @Inject
    public SnsInspectionController(SnsService snsService, ObjectMapper objectMapper) {
        this.snsService = snsService;
        this.objectMapper = objectMapper;
    }

    @GET
    public Response getMessages(@QueryParam("phone") String phone,
                                @QueryParam("id") String messageId) {
        List<SentSms> messages = snsService.getSentMessages();

        ArrayNode arr = objectMapper.createArrayNode();
        for (SentSms sms : messages) {
            if (phone != null && !phone.equals(sms.getPhoneNumber())) continue;
            if (messageId != null && !messageId.equals(sms.getMessageId())) continue;

            ObjectNode node = objectMapper.createObjectNode();
            node.put("Id", sms.getMessageId());
            if (sms.getRegion() != null) {
                node.put("Region", sms.getRegion());
            } else {
                node.putNull("Region");
            }
            node.put("PhoneNumber", sms.getPhoneNumber());
            node.put("Message", sms.getMessage());
            if (sms.getSubject() != null) {
                node.put("Subject", sms.getSubject());
            } else {
                node.putNull("Subject");
            }
            if (sms.getSentAt() != null) {
                node.put("Timestamp", sms.getSentAt().toString());
            }
            arr.add(node);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("messages", arr);
        return Response.ok(result).build();
    }

    @DELETE
    public Response clearMessages() {
        snsService.clearSentMessages();
        return Response.ok().build();
    }
}
