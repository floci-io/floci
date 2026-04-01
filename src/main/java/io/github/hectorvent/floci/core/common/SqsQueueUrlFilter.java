package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;

/**
 * Pre-matching filter that rewrites SQS JSON 1.0 requests sent to the queue URL path
 * (/{accountId}/{queueName}) to POST / so they are handled by AwsJsonController.
 *
 * Newer AWS SDKs (e.g. aws-sdk-sqs Ruby gem >= 1.71) route operations to the queue URL
 * rather than POST /. Without this filter, those requests match S3Controller's
 * /{bucket}/{key:.+} handler and return NoSuchBucket errors.
 */
@Provider
@PreMatching
public class SqsQueueUrlFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        String target = ctx.getHeaderString("X-Amz-Target");
        MediaType mt = ctx.getMediaType();

        if ("POST".equalsIgnoreCase(ctx.getMethod())
                && target != null && target.startsWith("AmazonSQS.")
                && mt != null
                && "application".equals(mt.getType())
                && "x-amz-json-1.0".equals(mt.getSubtype())) {
            URI rewritten = UriBuilder.fromUri(ctx.getUriInfo().getRequestUri())
                    .replacePath("/")
                    .build();
            ctx.setRequestUri(rewritten);
        }
    }
}
