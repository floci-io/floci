package io.floci.aws.services.s3;

import io.floci.aws.core.common.RequestUriRewrite;
import io.floci.aws.core.common.ResteasyRequests;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Routes a request for a key made only of slashes as the object request it is. {@code PUT /bucket//} writes the
 * key {@code /}, but JAX-RS collapses consecutive slashes before it matches a route, so the request would run as
 * the bucket's operation (CreateBucket, ListObjects, DeleteBucket) and be authorized as that one. The key's
 * slashes are escaped in the path the router matches, which keeps them inside the key, and {@code UriInfo}
 * decodes them back, so {@link S3Controller} and the IAM check both see the object the request names. A key with
 * anything besides slashes already reaches the object route, where the key is read from the path as sent
 * ({@link S3ObjectKeys}).
 *
 * <p>Runs after {@link S3VirtualHostFilter}, which has put a virtual-hosted request's bucket into its path, and
 * leaves alone a request signed for another service. An S3 Control route never matches as a bucket's own path,
 * since each has more than one segment, so a bucket named like its prefix, {@code v20180820}, is read as a bucket.
 */
@Provider
@PreMatching
@Priority(Priorities.USER + 1)
@ApplicationScoped
public class S3SlashOnlyKeyFilter implements ContainerRequestFilter {

    private static final Pattern BUCKET_PATH = Pattern.compile("/([^/]+)/?");

    private final CurrentVertxRequest currentVertxRequest;

    @Inject
    public S3SlashOnlyKeyFilter(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        RoutingContext routingContext = currentVertxRequest.getCurrent();
        String sentPath = routingContext == null || routingContext.request() == null
                ? null : routingContext.request().path();
        // A key of slashes alone ends the path with two, which every other request is passed on by.
        if (sentPath == null || !sentPath.endsWith("//") || !sentPath.startsWith("/")
                || signedForAnotherService(requestContext)) {
            return;
        }
        // Only a request the router would match as a bucket's own path, after any filter before this one rewrote it.
        URI requestUri = requestContext.getUriInfo().getRequestUri();
        Matcher routed = BUCKET_PATH.matcher(requestUri.getRawPath());
        if (!routed.matches()) {
            return;
        }
        String bucket = routed.group(1);
        String key;
        if (requestContext.getProperty(S3VirtualHostFilter.ORIGINAL_REQUEST_URI_PROPERTY) != null) {
            key = sentPath.substring(1);
        } else if (sentPath.startsWith("/" + bucket + "/")) {
            key = sentPath.substring(bucket.length() + 2);
        } else {
            return;
        }
        if (key.isEmpty() || !key.chars().allMatch(c -> c == '/')) {
            return;
        }
        URI objectUri = UriBuilder.fromUri(requestUri)
                .replacePath("/" + bucket + "/" + "%2F".repeat(key.length()))
                .build();
        RequestUriRewrite.apply(requestContext, objectUri);
    }

    private static boolean signedForAnotherService(ContainerRequestContext requestContext) {
        String authorization = requestContext.getHeaderString("Authorization");
        if (authorization != null && authorization.contains("Credential=")) {
            return !authorization.contains("/s3/aws4_request");
        }
        String credential = ResteasyRequests.queryParameters(requestContext).getFirst("X-Amz-Credential");
        return credential != null && !credential.contains("/s3/aws4_request");
    }
}
