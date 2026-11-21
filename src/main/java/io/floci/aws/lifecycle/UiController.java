package io.floci.aws.lifecycle;

import io.floci.aws.services.floci.ui.FlociUiManager;
import io.floci.aws.services.floci.ui.FlociUiManager.UiStatus;
import io.floci.aws.services.floci.ui.UiPages;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Browser-facing endpoints that launch and redirect to the floci-ui sidecar.
 *
 * <ul>
 *   <li>{@code GET /_floci/ui}: kicks off the sidecar start (async) and returns the
 *       landing page, which shows its wait view on this path: it polls {@code /status}
 *       and redirects when ready.</li>
 *   <li>{@code GET /_floci/ui/status}: JSON {@code {ready, url, error}} the
 *       interstitial polls.</li>
 * </ul>
 */
@Path("{prefix:(_floci|_localstack)}/ui")
public class UiController {

    private final FlociUiManager uiManager;
    private final UiPages uiPages;

    @Inject
    public UiController(FlociUiManager uiManager, UiPages uiPages) {
        this.uiManager = uiManager;
        this.uiPages = uiPages;
    }

    @GET
    @Produces(MediaType.TEXT_HTML)
    public Response ui() {
        uiManager.ensureStartedAsync();
        return Response.ok(uiPages.landingHtml()).build();
    }

    /**
     * The sidecar's state. The body is a map, as the other {@code /_floci} endpoints build theirs: a record needs
     * reflection metadata for its components that the native image did not have, and Jackson found no properties on
     * it there, so the endpoint answered 500 and the wait view never redirected.
     */
    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@HeaderParam("Host") String host) {
        UiStatus s = uiManager.status();
        if (s.error() != null) {
            return Response.ok(statusBody(false, null, s.error())).build();
        }
        if (s.ready() && s.hostPort() > 0) {
            return Response.ok(statusBody(true, redirectUrl(host, s.hostPort()), null)).build();
        }
        return Response.ok(statusBody(false, null, null)).build();
    }

    private static Map<String, Object> statusBody(boolean ready, String url, String error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ready", ready);
        body.put("url", url);
        body.put("error", error);
        return body;
    }

    /** Builds the browser-reachable UI URL from the request Host and the published port. */
    private static String redirectUrl(String host, int port) {
        String hostname = (host == null || host.isBlank()) ? "localhost" : host;
        int colon = hostname.indexOf(':');
        if (hostname.startsWith("[")) {
            int closingBracket = hostname.indexOf(']');
            if (closingBracket == hostname.length() - 1
                    || (closingBracket >= 0 && hostname.charAt(closingBracket + 1) == ':')) {
                colon = hostname.indexOf(':', closingBracket + 1);
            }
        }
        if (colon >= 0) {
            hostname = hostname.substring(0, colon);
        }
        return "http://" + hostname + ":" + port + "/";
    }
}
