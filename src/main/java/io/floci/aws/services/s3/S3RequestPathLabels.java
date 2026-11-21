package io.floci.aws.services.s3;

import io.floci.aws.core.common.RequestPathLabels;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;

import java.net.URI;
import java.util.Map;

/**
 * The key an S3 object request names, as {@link S3Controller} reads it ({@link S3ObjectKeys}): from the path as the
 * client sent it, where the key's own slashes survive, which JAX-RS collapses out of the path the route's labels are
 * read from. {@code PUT /bucket//email.txt} stores the key {@code /email.txt}, and would otherwise be checked on
 * {@code arn:aws:s3:::bucket/email.txt}, so a grant on that object would cover this one. The object stored, the
 * object the conditions describe and the object a policy is matched against are then the same one. A route naming no
 * key, as every bucket and S3 Control route, is read as the router reads it.
 */
@ApplicationScoped
public class S3RequestPathLabels implements RequestPathLabels {

    /** The current request, for its path as sent; null outside a request, where the request URI's raw path stands in. */
    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public S3RequestPathLabels(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    @Override
    public String namespace() {
        return "s3";
    }

    @Override
    public Map<String, String> labels(ContainerRequestContext ctx, Map<String, String> routed) {
        String bucket = routed.get("Bucket");
        if (bucket == null || !routed.containsKey("Key") || ctx.getUriInfo() == null) {
            return Map.of();
        }
        URI requestUri = ctx.getUriInfo().getRequestUri();
        RoutingContext current = currentVertxRequest == null ? null : currentVertxRequest.getCurrent();
        String sentPath = current != null && current.request() != null ? current.request().path()
                : requestUri == null ? null : requestUri.getRawPath();
        String key = S3ObjectKeys.objectKey(sentPath, requestUri == null ? null : requestUri.getPath(),
                ctx.getUriInfo().getPath(), bucket);
        return key == null ? Map.of() : Map.of("Key", key);
    }
}
