package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.services.ses.model.SentEmail;
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
 * LocalStack-compatible REST endpoint for inspecting sent SES emails.
 * Provides GET /_aws/ses and DELETE /_aws/ses for test helpers.
 */
@Path("/_aws/ses")
@Produces(MediaType.APPLICATION_JSON)
public class SesInspectionController {

    private final SesService sesService;
    private final ObjectMapper objectMapper;

    @Inject
    public SesInspectionController(SesService sesService, ObjectMapper objectMapper) {
        this.sesService = sesService;
        this.objectMapper = objectMapper;
    }

    @GET
    public Response getEmails(@QueryParam("id") String messageId) {
        List<SentEmail> emails = sesService.getEmails();

        ArrayNode messages = objectMapper.createArrayNode();
        for (SentEmail email : emails) {
            if (messageId != null && !messageId.equals(email.getMessageId())) {
                continue;
            }
            ObjectNode node = objectMapper.createObjectNode();
            node.put("Id", email.getMessageId());
            if (email.getRegion() != null) {
                node.put("Region", email.getRegion());
            } else {
                node.putNull("Region");
            }
            node.put("Source", email.getSource());

            if (email.isRaw()) {
                // LocalStack returns RawData for raw emails, without
                // Destination / Subject / Body fields.
                node.put("RawData", email.getRawData());
            } else {
                ObjectNode destination = node.putObject("Destination");
                if (email.getToAddresses() != null) {
                    ArrayNode toArr = destination.putArray("ToAddresses");
                    email.getToAddresses().forEach(toArr::add);
                }
                if (email.getCcAddresses() != null) {
                    ArrayNode ccArr = destination.putArray("CcAddresses");
                    email.getCcAddresses().forEach(ccArr::add);
                }
                if (email.getBccAddresses() != null) {
                    ArrayNode bccArr = destination.putArray("BccAddresses");
                    email.getBccAddresses().forEach(bccArr::add);
                }

                if (email.getReplyToAddresses() != null && !email.getReplyToAddresses().isEmpty()) {
                    ArrayNode replyTo = node.putArray("ReplyToAddresses");
                    email.getReplyToAddresses().forEach(replyTo::add);
                }

                node.put("Subject", email.getSubject());

                ObjectNode body = node.putObject("Body");
                if (email.getBodyText() != null) {
                    body.put("text_part", email.getBodyText());
                } else {
                    body.putNull("text_part");
                }
                if (email.getBodyHtml() != null) {
                    body.put("html_part", email.getBodyHtml());
                } else {
                    body.putNull("html_part");
                }
            }

            if (email.getSentAt() != null) {
                node.put("Timestamp", email.getSentAt().toString());
            }

            messages.add(node);
        }

        ObjectNode result = objectMapper.createObjectNode();
        result.set("messages", messages);
        return Response.ok(result).build();
    }

    @DELETE
    public Response clearEmails() {
        sesService.clearEmails();
        return Response.ok().build();
    }
}
