package io.github.hectorvent.floci.services.s3;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Strips the {@code charset} parameter from {@code application/xml} and
 * {@code text/xml} response Content-Type headers so they match the real AWS
 * XML protocol wire format.
 */
@Provider
@ApplicationScoped
public class S3ContentTypeCharsetFilter implements ContainerRequestFilter {

    @Inject
    CurrentVertxRequest currentVertxRequest;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        RoutingContext rc = currentVertxRequest.getCurrent();
        if (rc == null) return;
        rc.addHeadersEndHandler(v -> {
            String ct = rc.response().headers().get("Content-Type");
            if (ct == null) return;
            int semi = ct.indexOf(';');
            if (semi < 0) return;
            String base = ct.substring(0, semi).trim();
            if ("application/xml".equalsIgnoreCase(base) || "text/xml".equalsIgnoreCase(base)) {
                rc.response().headers().set("Content-Type", base);
            }
        });
    }
}
