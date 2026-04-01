package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * Pre-matching filter that rewrites SQS JSON 1.0 requests sent to the queue URL path
 * (/{accountId}/{queueName}) to POST / so they are handled by AwsJsonController.
 * <p>
 * Newer AWS SDKs (e.g. aws-sdk-sqs Ruby gem >= 1.71) route operations to the queue URL
 * rather than POST /. Without this filter, those requests match S3Controller's
 * /{bucket}/{key:.+} handler and return NoSuchBucket errors.
 */
@Provider
@PreMatching
public class SqsQueueUrlRouterFilter implements ContainerRequestFilter {

    private static final Pattern QUEUE_PATH = Pattern.compile("^/(\\d+)/([^/]+)$");

    @Override
    public void filter(ContainerRequestContext ctx) {

        if (!"POST".equals(ctx.getMethod())) {
            return;
        }

        String path = ctx.getUriInfo().getPath();
        if (!QUEUE_PATH.matcher(path).matches()) {
            return;
        }

        MediaType mt = ctx.getMediaType();
        if (mt == null) {
            return;
        }

        boolean isSqsJson = "application".equals(mt.getType())
                && "x-amz-json-1.0".equals(mt.getSubtype())
                && isSqsTarget(ctx.getHeaderString("X-Amz-Target"));

        // S3 never receives form-encoded POSTs to /{bucket}/{key} paths —
        // S3 presigned POST always goes to /{bucket}, not /{bucket}/{key}.
        boolean isSqsQuery = "application".equals(mt.getType())
                && "x-www-form-urlencoded".equals(mt.getSubtype());

        if (!isSqsJson && !isSqsQuery) {
            return;
        }

        URI rewritten = ctx.getUriInfo().getRequestUriBuilder()
                .replacePath("/")
                .build();
        ctx.setRequestUri(rewritten);
    }

    private boolean isSqsTarget(String target) {
        return target != null && target.startsWith("AmazonSQS.");
    }
}
