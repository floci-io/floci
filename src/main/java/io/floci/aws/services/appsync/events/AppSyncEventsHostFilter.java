package io.floci.aws.services.appsync.events;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RequestHost;
import io.floci.aws.core.common.RequestUriRewrite;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;

/**
 * Moves an HTTP publish sent to an Events API's own host, {@code POST /event} on
 * {@code <apiId>.appsync-api.<region>.<suffix>}, onto the path form
 * {@code /_aws/appsync/events/<apiId>/event} that {@link AppSyncEventsController} serves, and
 * records the path the caller signed. The WebSocket host is upgraded by
 * {@link AppSyncEventsWebSocketHandler} before JAX-RS sees it.
 */
@Provider
@PreMatching
@Priority(9)
@ApplicationScoped
public class AppSyncEventsHostFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(AppSyncEventsHostFilter.class);
    static final String EVENT_PATH = "/event";

    private final EmulatorConfig config;
    private final AppSyncEventsRequestPath requestPath;

    @Inject
    public AppSyncEventsHostFilter(EmulatorConfig config, AppSyncEventsRequestPath requestPath) {
        this.config = config;
        this.requestPath = requestPath;
    }

    @Override
    public void filter(ContainerRequestContext ctx) {
        String host = RequestHost.of(ctx);
        String apiId = AppSyncEventsHosts.extractApiId(host, AppSyncEventsHosts.HTTP_LABEL,
                config.hostname().orElse("localhost"));
        if (apiId == null) {
            return;
        }
        URI original = ctx.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (!EVENT_PATH.equals(path) && !(EVENT_PATH + "/").equals(path)) {
            return;
        }
        URI rewritten = UriBuilder.fromUri(original)
                .replacePath(AppSyncEventsController.PATH_PREFIX + "/" + apiId + EVENT_PATH)
                .buildFromEncoded();
        LOG.debugv("Events host routing: {0}{1} -> {2}", host, path, rewritten.getRawPath());
        requestPath.recordSignedPath(path);
        RequestUriRewrite.apply(ctx, rewritten);
    }
}
