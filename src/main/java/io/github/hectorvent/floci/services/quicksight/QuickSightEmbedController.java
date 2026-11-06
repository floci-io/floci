package io.github.hectorvent.floci.services.quicksight;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;

@Path("/embed/{session}")
public class QuickSightEmbedController {
    private final QuickSightService service;

    @Inject
    public QuickSightEmbedController(QuickSightService service) {
        this.service = service;
    }

    @GET
    @Path("/dashboards/{id}")
    @Produces(MediaType.TEXT_HTML)
    public Response dashboard(@PathParam("session") String session, @PathParam("id") String id,
                              @QueryParam("code") String code, @HeaderParam("Referer") String referer,
                              @CookieParam("floci-quicksight") String cookie) {
        String origin = origin(referer);
        ObjectNode dashboard = service.redeem(session, code, id, origin, cookie);
        String name = escape(dashboard.path("Name").asText());
        String html = "<!doctype html><html><head><meta charset=\"utf-8\"><title>" + name
                + "</title></head><body><main><h1>" + name + "</h1><p>Local QuickSight metadata preview. BI visualizations are not rendered.</p></main></body></html>";
        Response.ResponseBuilder response = Response.ok(html).header("Cache-Control", "no-store")
                .header("Set-Cookie", "floci-quicksight=" + dashboard.path("_SessionCookie").asText() + "; Path=/embed/" + session + "; HttpOnly; Secure; SameSite=None");
        if (!origin.isEmpty()) {
            response.header("Content-Security-Policy", "frame-ancestors " + origin);
        }
        return response.build();
    }

    private String origin(String referer) {
        if (referer == null) {
            return "";
        }
        try {
            URI uri = URI.create(referer);
            return uri.getHost() == null ? "" : uri.getScheme() + "://" + uri.getRawAuthority();
        } catch (IllegalArgumentException exception) {
            return "";
        }
    }

    private String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
