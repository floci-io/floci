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

/**
 * Serves the functional list-management unsubscribe link that {@code SendEmail} injects into a
 * single-recipient {@code ListManagementOptions} message (the {@code List-Unsubscribe} header and the
 * {@code {{amazonSESUnsubscribeUrl}}} body placeholder). AWS hosts this on an opaque endpoint; Floci
 * hosts its own so the link actually resolves and updates the contact's subscription.
 *
 * <p>{@code GET} handles a browser click; {@code POST} handles the RFC 8058 one-click request. Both
 * opt the contact out of the given topic (or the whole list when no topic is supplied).
 */
@Path("/_aws/ses/unsubscribe")
public class SesUnsubscribeController {

    private final SesService sesService;

    @Inject
    public SesUnsubscribeController(SesService sesService) {
        this.sesService = sesService;
    }

    @GET
    @Produces(MediaType.TEXT_PLAIN)
    public Response unsubscribeGet(@QueryParam("region") String region,
                                   @QueryParam("contactList") String contactList,
                                   @QueryParam("topic") String topic,
                                   @QueryParam("address") String address) {
        return unsubscribe(region, contactList, topic, address);
    }

    @POST
    @Produces(MediaType.TEXT_PLAIN)
    public Response unsubscribePost(@QueryParam("region") String region,
                                    @QueryParam("contactList") String contactList,
                                    @QueryParam("topic") String topic,
                                    @QueryParam("address") String address) {
        return unsubscribe(region, contactList, topic, address);
    }

    private Response unsubscribe(String region, String contactList, String topic, String address) {
        // The injected unsubscribe link always carries the region, so require it rather than
        // defaulting — defaulting could opt a contact out in the wrong region.
        if (region == null || region.isBlank() || contactList == null || contactList.isBlank()
                || address == null || address.isBlank()) {
            return Response.status(400).entity("region, contactList and address are required.").build();
        }
        try {
            sesService.unsubscribeContact(contactList, address, topic, region);
        } catch (AwsException e) {
            return Response.status(e.getHttpStatus()).entity(e.getMessage()).build();
        }
        String scope = (topic == null || topic.isBlank()) ? "all topics" : "topic '" + topic + "'";
        return Response.ok(address + " has been unsubscribed from " + scope
                + " on contact list '" + contactList + "'.").build();
    }
}
