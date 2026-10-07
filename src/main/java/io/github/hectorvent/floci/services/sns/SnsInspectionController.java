package io.github.hectorvent.floci.services.sns;

import io.github.hectorvent.floci.services.sns.model.SentSms;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
 * <p>GET  /_aws/sns/SimpleNotificationService-&lt;fingerprint&gt;.pem: the certificate deliveries
 *     are signed with, the {@code SigningCertURL} of every signed message
 */
@Path("/_aws/sns")
@Produces(MediaType.APPLICATION_JSON)
public class SnsInspectionController {

    private static final String PEM_MEDIA_TYPE = "application/x-pem-file";

    private final SnsService snsService;
    private final ObjectMapper objectMapper;
    private final SnsMessageSigner messageSigner;

    @Inject
    public SnsInspectionController(SnsService snsService, ObjectMapper objectMapper,
                                   SnsMessageSigner messageSigner) {
        this.snsService = snsService;
        this.objectMapper = objectMapper;
        this.messageSigner = messageSigner;
    }

    @GET
    @Path("{certificate: SimpleNotificationService-[0-9a-f]+\\.pem}")
    @Produces(PEM_MEDIA_TYPE)
    public Response getSigningCertificate(@PathParam("certificate") String certificate) {
        return messageSigner.certificatePem(certificate)
                .map(pem -> Response.ok(pem, PEM_MEDIA_TYPE).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
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
