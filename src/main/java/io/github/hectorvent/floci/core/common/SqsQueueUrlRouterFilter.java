package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Pre-matching filter that rewrites SQS requests sent to the queue URL path
 * (/{accountId}/{queueName}) to POST / so they are handled by the correct controller.
 * <p>
 * Newer AWS SDKs (e.g. aws-sdk-sqs Ruby gem >= 1.71) route operations to the queue URL
 * rather than POST /. Without this filter, those requests match S3Controller's
 * /{bucket}/{key:.+} handler and return NoSuchBucket errors.
 * <p>
 * For the query (form-encoded) protocol, AWS SDK v1 omits QueueUrl from the body and
 * uses the queue URL as the HTTP path instead. This filter appends it back into the
 * entity stream so SqsQueryHandler can look up the queue normally.
 */
@Provider
@PreMatching
public class SqsQueueUrlRouterFilter implements ContainerRequestFilter {

    /**
     * The number of bytes this filter appended to a Query body, set only when it appended any.
     * {@link SigV4HeaderSignatureFilter} verifies the body the client signed, which ends before them.
     */
    public static final String APPENDED_BODY_BYTES_PROPERTY =
            SqsQueueUrlRouterFilter.class.getName() + ".appendedBodyBytes";

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

        // Reconstruct the queue URL from the original path.
        URI reqUri = ctx.getUriInfo().getRequestUri();
        String queueUrl = reqUri.getScheme() + "://" + reqUri.getAuthority() + path;

        if (isSqsQuery) {
            // AWS SDK v1 omits QueueUrl from the form body and uses the queue URL as the
            // HTTP path instead. Append it to the entity stream so SqsQueryHandler gets it
            // naturally from form params — no changes needed in AwsQueryController.
            byte[] injection = ("&QueueUrl=" + URLEncoder.encode(queueUrl, StandardCharsets.UTF_8))
                    .getBytes(StandardCharsets.UTF_8);
            ctx.setEntityStream(new SequenceInputStream(ctx.getEntityStream(),
                    new ByteArrayInputStream(injection)));
            ctx.setProperty(APPENDED_BODY_BYTES_PROPERTY, injection.length);
            String cl = ctx.getHeaderString("Content-Length");
            if (cl != null) {
                ctx.getHeaders().putSingle("Content-Length",
                        String.valueOf(Long.parseLong(cl) + injection.length));
            }
        }

        // Rewrite the path to / so AwsQueryController / AwsJsonController handles the request.
        ctx.setRequestUri(ctx.getUriInfo().getRequestUriBuilder()
                .replacePath("/")
                .build());
    }

    private boolean isSqsTarget(String target) {
        return target != null && target.startsWith("AmazonSQS.");
    }
}
