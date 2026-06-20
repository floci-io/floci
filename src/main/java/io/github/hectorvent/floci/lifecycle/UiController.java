package io.github.hectorvent.floci.lifecycle;

import io.github.hectorvent.floci.services.floci.ui.FlociUiManager;
import io.github.hectorvent.floci.services.floci.ui.FlociUiManager.UiStatus;
import io.github.hectorvent.floci.services.floci.ui.UiPages;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Browser-facing endpoints that launch and redirect to the floci-ui sidecar.
 *
 * <ul>
 *   <li>{@code GET /_floci/ui} — kicks off the sidecar start (async) and returns a
 *       small interstitial page that polls {@code /status} and redirects when ready.</li>
 *   <li>{@code GET /_floci/ui/status} — JSON {@code {ready, url, error}} the
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
        return Response.ok(uiPages.startingHtml()).build();
    }

    @RegisterForReflection
    public record StatusResponse(boolean ready, String url, String error) {}

    @GET
    @Path("/status")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@HeaderParam("Host") String host) {
        UiStatus s = uiManager.status();
        if (s.error() != null) {
            return Response.ok(new StatusResponse(false, null, s.error())).build();
        }
        if (s.ready() && s.hostPort() > 0) {
            return Response.ok(new StatusResponse(true, redirectUrl(host, s.hostPort()), null)).build();
        }
        return Response.ok(new StatusResponse(false, null, null)).build();
    }

    /** Builds the browser-reachable UI URL from the request Host and the published port. */
    private static String redirectUrl(String host, int port) {
        String hostname = (host == null || host.isBlank()) ? "localhost" : host;
        int colon = hostname.indexOf(':');
        if (colon >= 0) {
            hostname = hostname.substring(0, colon);
        }
        return "http://" + hostname + ":" + port + "/";
    }
}
