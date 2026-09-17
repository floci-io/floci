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
 * {@code Host} header, while HTTP/1.1 sends {@code Host}. Floci routes many requests by the host
 * a client addressed: CloudFront distribution domains, S3 virtual-hosted buckets, API Gateway
 * execute-api and custom domains, Lambda function URLs, and Cognito custom domains. Those lookups
 * read the {@code Host} header, so without this filter every one of them falls through whenever a
 * client negotiates HTTP/2, which curl, the AWS SDK for Java and Go, and browsers all do over
 * HTTPS.
 *
 * <p>The header is set on the Vert.x request itself, so both JAX-RS filters and the handlers that
 * read the raw request see the same value. A request that already carries {@code Host} is left
 * alone.
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
