package io.github.hectorvent.floci.core.common;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.net.HostAndPort;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Copies the HTTP/2 {@code :authority} pseudo-header into a {@code Host} header.
 *
 * <p>HTTP/2 (RFC 9113 section 8.3.1) carries the authority in {@code :authority} and sends no
 * {@code Host} header. Floci routes by host header, so without this every host-based lookup falls
 * through over HTTPS, where curl, the AWS SDKs and browsers negotiate HTTP/2.
 *
 * <p>The header is set on the Vert.x request, so JAX-RS filters and raw request handlers see the
 * same value. Reverse-proxy rewriting is a separate question, answered by {@code X-Forwarded-Host}
 * where a service reads it.
 */
@ApplicationScoped
public class Http2HostHeaderFilter {

    void register(@Observes Filters filters) {
        filters.register(Http2HostHeaderFilter::applyAuthority, Integer.MAX_VALUE);
    }

    static void applyAuthority(RoutingContext context) {
        HttpServerRequest request = context.request();
        if (request.version() == HttpVersion.HTTP_2 && request.getHeader("Host") == null) {
            HostAndPort authority = request.authority();
            if (authority != null) {
                request.headers().set("Host", authority.toString());
            }
        }
        context.next();
    }
}
