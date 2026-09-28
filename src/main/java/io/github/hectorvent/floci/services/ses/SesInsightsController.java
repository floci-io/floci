package io.github.hectorvent.floci.services.ses;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ses.model.EmailInsights;
import io.github.hectorvent.floci.services.ses.model.InsightsBounce;
import io.github.hectorvent.floci.services.ses.model.InsightsComplaint;
import io.github.hectorvent.floci.services.ses.model.InsightsEvent;
import io.github.hectorvent.floci.services.ses.model.InsightsEventDetails;
import io.github.hectorvent.floci.services.ses.model.MessageTag;
import io.github.hectorvent.floci.services.ses.model.SentEmail;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

import static io.github.hectorvent.floci.services.ses.SesV2Json.putTimestamp;

/**
 * SES V2 message insights ({@code /v2/email/insights/{MessageId}/}), the read side of the
 * per-recipient timelines {@link SesMessageInsights} derives at send time.
 *
 * <p>Two behaviours are probe-confirmed against real SES (2026-09-21, us-east-1). The operation is
 * gated on Virtual Deliverability Manager and the gate is checked before the message id, so an
 * unparseable id in a VDM-disabled region still reports the VDM error. A message that is absent
 * reports the account id alongside the id.
 */
@Path("/v2/email")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SesInsightsController {

    private final SesSentEmailService sentEmailService;
    private final SesAccountService accountService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public SesInsightsController(SesSentEmailService sentEmailService, SesAccountService accountService,
                                 RegionResolver regionResolver, ObjectMapper objectMapper) {
        this.sentEmailService = sentEmailService;
        this.accountService = accountService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    @GET
    @Path("/insights/{messageId}")
    public Response getMessageInsights(@Context HttpHeaders headers,
                                       @PathParam("messageId") String messageId) {
        String region = regionResolver.resolveRegion(headers);
        accountService.requireVdmEnabled(region);
        SentEmail email = sentEmailService.find(region, messageId)
                .orElseThrow(() -> new AwsException("NotFoundException",
                        "Message <" + messageId + "> not found for account <"
                                + regionResolver.getAccountId() + ">", 404));
        return Response.ok(render(email)).build();
    }

    private ObjectNode render(SentEmail email) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("MessageId", email.getMessageId());
        if (email.getSource() != null) {
            result.put("FromEmailAddress", email.getSource());
        }
        if (email.getSubject() != null) {
            result.put("Subject", email.getSubject());
        }
        ArrayNode tags = result.putArray("EmailTags");
        for (MessageTag tag : orEmpty(email.getEmailTags())) {
            ObjectNode node = tags.addObject();
            node.put("Name", tag.name());
            node.put("Value", tag.value());
        }
        ArrayNode insights = result.putArray("Insights");
        for (EmailInsights recipient : orEmpty(email.getInsights())) {
            insights.add(renderRecipient(recipient));
        }
        return result;
    }

    private ObjectNode renderRecipient(EmailInsights recipient) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("Destination", recipient.destination());
        if (recipient.isp() != null) {
            node.put("Isp", recipient.isp());
        }
        ArrayNode events = node.putArray("Events");
        for (InsightsEvent event : orEmpty(recipient.events())) {
            events.add(renderEvent(event));
        }
        return node;
    }

    private ObjectNode renderEvent(InsightsEvent event) {
        ObjectNode node = objectMapper.createObjectNode();
        putTimestamp(node, "Timestamp", event.timestamp());
        node.put("Type", event.type());
        InsightsEventDetails details = event.details();
        if (details == null) {
            return node;
        }
        ObjectNode rendered = objectMapper.createObjectNode();
        InsightsBounce bounce = details.bounce();
        if (bounce != null) {
            ObjectNode node2 = rendered.putObject("Bounce");
            putIfPresent(node2, "BounceType", bounce.bounceType());
            putIfPresent(node2, "BounceSubType", bounce.bounceSubType());
            putIfPresent(node2, "DiagnosticCode", bounce.diagnosticCode());
        }
        InsightsComplaint complaint = details.complaint();
        if (complaint != null) {
            ObjectNode node2 = rendered.putObject("Complaint");
            putIfPresent(node2, "ComplaintSubType", complaint.complaintSubType());
            putIfPresent(node2, "ComplaintFeedbackType", complaint.complaintFeedbackType());
        }
        if (!rendered.isEmpty()) {
            node.set("Details", rendered);
        }
        return node;
    }

    private static void putIfPresent(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
