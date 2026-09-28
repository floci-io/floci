package io.github.hectorvent.floci.core.common;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Gives an HTTP/1.0 request that omitted {@code Host} the authority it never sent.
 *
 * <p>{@code Host} is mandatory only from HTTP/1.1 on: Vert.x answers 400 to a 1.1 request without
 * one and exempts 1.0 by design. A 1.0 request then reaches the application with no authority
 * anywhere, so {@code HttpServerRequest.absoluteURI()} is null and
 * {@code UriInfo.getRequestUri()} fails inside {@code new URI(null)}. Every host-routing filter
 * reads the request URI, through {@link RequestHost} or directly, so a plain
 * {@code GET /_floci/health HTTP/1.0} answered 500 on a healthy emulator, which reads as a hard
 * outage to a probe written without a {@code Host} header. Nothing about it is health-specific:
 * every endpoint answered 500 the same way.
 *
 * <p>The repair is the request URI rather than the {@code Host} header. Vert.x resolves the
 * authority once, in {@code RoutingContextImpl.route()}, before any handler runs, and caches the
 * absence; a header set afterwards is never looked at again. Rewriting the JAX-RS request URI
 * instead gives {@code UriInfo} the scheme, authority and path it needs, which is the same
 * mechanism the virtual-host filters use to reroute a request.
 *
 * <p>An origin-form target names no host anywhere, so the authority filled in for one is the local
 * socket the request arrived on, the address the client actually reached. It carries no virtual-host
 * meaning: an address literal names no S3 bucket, Lambda function URL, or custom domain, so the
 * request routes path-style, which is what a client sending no {@code Host} asked for.
 *
 * <p>An absolute-form target, which RFC 9112 section 3.2.2 requires a server to accept, states an
 * authority of its own: {@code GET http://bucket.s3.localhost:4566/key HTTP/1.0} names a bucket and
 * has to keep routing to it. It is broken without a {@code Host} header just the same, because the
 * authority vertx-web builds its absolute URI from is that header alone. Prepending the local socket
 * to a target that is already absolute would corrupt it instead: {@code URI} reads the concatenation
 * as the authority {@code 127.0.0.1:4566http:} and the path {@code //bucket.s3.localhost:4566/key}.
 * Asterisk-form names no host at all and is left alone, as it is with a {@code Host} header.
 */
@Provider
@PreMatching
@Priority(1) // ahead of every host-routing filter, the lowest of which is LambdaUrlRoutingFilter (5)
@ApplicationScoped
public class MissingHostHeaderFilter implements ContainerRequestFilter {

    private static final Logger LOG = Logger.getLogger(MissingHostHeaderFilter.class);

    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public MissingHostHeaderFilter(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        RoutingContext routingContext = currentVertxRequest.getCurrent();
        if (routingContext == null) {
            return;
        }
        // An HTTP/2 request has no Host header either, but carries :authority, which Vert.x
        // surfaces here. A null authority is the one case with nothing to route by.
        HttpServerRequest request = routingContext.request();
        if (request.authority() != null) {
            return;
        }
        String authority = requestAuthority(request);
        if (authority == null) {
            return;
        }
        // The scheme is the connection's rather than the target's, which is what vertx-web itself
        // uses once a Host header supplies the authority.
        URI requestUri = URI.create(request.scheme() + "://" + authority + pathAndQuery(request));
        // The two-argument form: the one-argument one derives the base URI from UriInfo, which is
        // the accessor that fails without an authority.
        requestContext.setRequestUri(requestUri.resolve("/"), requestUri);
        LOG.debugv("Request without an authority: {0} -> {1}", request.uri(), requestUri);
    }

    /**
     * The authority to route by: the one an absolute-form target states, the local socket for an
     * origin-form one. Null when the target names no host and none can stand in for it.
     */
    static String requestAuthority(HttpServerRequest request) {
        String target = request.uri();
        if (target.startsWith("/")) {
            return localAuthority(request.localAddress());
        }
        URI absolute;
        try {
            absolute = new URI(target);
        } catch (URISyntaxException malformed) {
            LOG.debugv("Request target is neither origin-form nor an absolute URI: {0}", target);
            return null;
        }
        // Asterisk-form and authority-form land here with no authority to read.
        return absolute.isAbsolute() ? absolute.getRawAuthority() : null;
    }

    /** The origin-form tail, which Vert.x parses out of an absolute-form target as well. */
    private static String pathAndQuery(HttpServerRequest request) {
        String query = request.query();
        return query == null ? request.path() : request.path() + "?" + query;
    }

    /** {@code host:port}, bracketing an IPv6 literal the way RFC 3986 requires. */
    static String localAuthority(SocketAddress localAddress) {
        if (localAddress == null || localAddress.host() == null) {
            return null;
        }
        String host = localAddress.host();
        if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
            host = "[" + host + "]";
        }
        return localAddress.port() > 0 ? host + ":" + localAddress.port() : host;
    }
}
