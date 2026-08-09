package io.github.hectorvent.floci.services.ses;

import io.github.hectorvent.floci.core.common.AwsException;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Serves the functional list-management unsubscribe link that {@code SendEmail} injects into a
 * single-recipient {@code ListManagementOptions} message (the {@code List-Unsubscribe} header and the
 * {@code {{amazonSESUnsubscribeUrl}}} body placeholder). AWS hosts this on an opaque endpoint; Floci
 * hosts its own so the link actually resolves and updates the contact's subscription.
 *
 * <p>{@code GET} handles a browser click and only renders a confirmation page — it does not change any
 * subscription, matching the AWS landing-page behavior and avoiding the RFC 8058 hazard where mail
 * clients or link-preview bots that prefetch the URL would otherwise silently opt the contact out.
 * The actual opt-out runs on {@code POST} (the RFC 8058 one-click request the confirmation form
 * submits), which opts the contact out of the given topic, or the whole list when no topic is given.
 *
 * <p>Unlike AWS's opaque hosted token, this endpoint carries the target in readable query parameters
 * and — like the rest of Floci — is unauthenticated, so a {@code POST} can opt out any contact.
 * That is acceptable only because Floci is a local emulator for a trusted dev/test network; it must
 * not be exposed on an untrusted network.
 */
@Path("/_aws/ses/unsubscribe")
public class SesUnsubscribeController {

    private final SesService sesService;

    @Inject
    public SesUnsubscribeController(SesService sesService) {
        this.sesService = sesService;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response unsubscribeGet(@QueryParam("region") String region,
                                   @QueryParam("contactList") String contactList,
                                   @QueryParam("topic") String topic,
                                   @QueryParam("address") String address) {
        Response invalid = validate(region, contactList, address);
        if (invalid != null) {
            return invalid;
        }
        // A browser click only lands on a confirmation page; the opt-out is performed by the POST that
        // the form submits, so a client or bot prefetching the link cannot unsubscribe the contact.
        String html = "<!doctype html>\n"
                + "<html lang=\"en\"><head><meta charset=\"utf-8\"><title>Unsubscribe</title></head>\n"
                + "<body>\n"
                + "<p>Unsubscribe " + htmlEscape(address) + " from " + htmlEscape(scopeLabel(topic))
                + " on contact list &#39;" + htmlEscape(contactList) + "&#39;?</p>\n"
                + "<form method=\"post\" action=\""
                + htmlEscape(formAction(region, contactList, topic, address)) + "\">\n"
                // RFC 8058 one-click parameter, so the form's POST body matches the advertised
                // List-Unsubscribe-Post: List-Unsubscribe=One-Click header.
                + "<input type=\"hidden\" name=\"List-Unsubscribe\" value=\"One-Click\">\n"
                + "<button type=\"submit\">Unsubscribe</button>\n"
                + "</form>\n"
                + "</body></html>\n";
        return Response.ok(html).build();
    }

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    public Response unsubscribePost(@QueryParam("region") String region,
                                    @QueryParam("contactList") String contactList,
                                    @QueryParam("topic") String topic,
                                    @QueryParam("address") String address) {
        Response invalid = validate(region, contactList, address);
        if (invalid != null) {
            return invalid;
        }
        try {
            sesService.unsubscribeContact(contactList, address, topic, region);
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).entity(e.getMessage()).build();
        }
        return Response.ok(address + " has been unsubscribed from " + scopeLabel(topic)
                + " on contact list '" + contactList + "'.").build();
    }

    private Response validate(String region, String contactList, String address) {
        // The injected unsubscribe link always carries the region, so require it rather than
        // defaulting — defaulting could opt a contact out in the wrong region.
        if (region == null || region.isBlank() || contactList == null || contactList.isBlank()
                || address == null || address.isBlank()) {
            return Response.status(400).entity("region, contactList and address are required.").build();
        }
        return null;
    }

    private static String scopeLabel(String topic) {
        return (topic == null || topic.isBlank()) ? "all topics" : "topic '" + topic + "'";
    }

    private static String formAction(String region, String contactList, String topic, String address) {
        StringBuilder sb = new StringBuilder("?region=").append(enc(region))
                .append("&contactList=").append(enc(contactList))
                .append("&address=").append(enc(address));
        if (topic != null && !topic.isBlank()) {
            sb.append("&topic=").append(enc(topic));
        }
        return sb.toString();
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String htmlEscape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
