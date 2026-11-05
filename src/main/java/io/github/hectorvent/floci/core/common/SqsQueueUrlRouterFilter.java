package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-matching filter that rewrites SQS requests sent to a queue URL path to POST / so they are handled
 * by the correct controller. The path is /{accountId}/{queueName}, optionally behind a /queue/{region}/
 * prefix, with an optional trailing slash.
 * <p>
 * Newer AWS SDKs (e.g. aws-sdk-sqs Ruby gem >= 1.71) route operations to the queue URL
 * rather than POST /. Without this filter, those requests match S3Controller's
 * /{bucket}/{key:.+} handler and return NoSuchBucket errors.
 * <p>
 * For the query (form-encoded) protocol, AWS SDK v1 omits QueueUrl from the body and
 * uses the queue URL as the HTTP path instead. This filter appends it back into the
 * entity stream so SqsQueryHandler can look up the queue normally. Like AWS, a query request
 * is only rewritten on the unprefixed path, because the path names the queue. A query request signed
 * for SQS on the prefixed path is rejected with 404 InvalidAddress, as AWS rejects it, by
 * {@link SqsInvalidAddressFilter} once its signature has been verified.
 * <p>
 * S3 object POSTs, which always carry uploads, uploadId, restore or select, are never rewritten.
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

    /**
     * Set on a query request signed for SQS on a /queue/{region}/ path, which {@link SqsInvalidAddressFilter}
     * rejects with 404 InvalidAddress after {@link SigV4HeaderSignatureFilter} has verified its signature.
     */
    public static final String INVALID_ADDRESS_PROPERTY =
            SqsQueueUrlRouterFilter.class.getName() + ".invalidAddress";

    // The queue URL path (/{account}/{queue}), optionally behind a /queue/{region}/ prefix, with an optional
    // trailing slash, as the AWS SDK v2 may append one. Like AWS, a JSON request is routed on either form because
    // it names the queue in its QueueUrl body field, while a Query request is named by its path and is routed
    // only without the prefix.
    private static final Pattern QUEUE_PATH = Pattern.compile("^/(queue/[a-z0-9-]+/)?(\\d+)/([^/]+)/?$");

    // S3 POSTs to /{bucket}/{key} always carry one of these parameters, and SQS never sends them.
    private static final List<String> S3_OBJECT_POST_PARAMS = List.of("uploads", "uploadId", "restore", "select");

    @Override
    public void filter(ContainerRequestContext ctx) {

        if (!"POST".equals(ctx.getMethod())) {
            return;
        }

        String path = ctx.getUriInfo().getPath();
        Matcher matcher = QUEUE_PATH.matcher(path);
        if (!matcher.matches()) {
            return;
        }
        boolean hasRegionPrefix = matcher.group(1) != null;

        MultivaluedMap<String, String> queryParams = ctx.getUriInfo().getQueryParameters();
        for (String param : S3_OBJECT_POST_PARAMS) {
            if (queryParams.containsKey(param)) {
                return;
            }
        }

        MediaType mt = ctx.getMediaType();
        if (mt == null) {
            return;
        }

        boolean isSqsJson = "application".equals(mt.getType())
                && "x-amz-json-1.0".equals(mt.getSubtype())
                && isSqsTarget(ctx.getHeaderString("X-Amz-Target"));

        // A form-encoded S3 POST to /{bucket}/{key} carries one of S3_OBJECT_POST_PARAMS, handled above.
        // S3 presigned POST always goes to /{bucket}, not /{bucket}/{key}.
        boolean isSqsQuery = "application".equals(mt.getType())
                && "x-www-form-urlencoded".equals(mt.getSubtype());

        if (isSqsQuery && hasRegionPrefix) {
            // AWS does not read a queue from this path. The signing scope names the service, as the AWS endpoint
            // does, so the body need not be read here, and requests for other services keep their routing. The
            // request is routed to POST / so that its signature is verified before it is rejected.
            if (SignedServiceRoute.signingService(ctx).filter("sqs"::equals).isPresent()) {
                ctx.setProperty(INVALID_ADDRESS_PROPERTY, Boolean.TRUE);
                rewriteToRoot(ctx);
            }
            return;
        }

        if (!isSqsJson && !isSqsQuery) {
            return;
        }

        // Reconstruct the queue URL from the original path.
        URI reqUri = ctx.getUriInfo().getRequestUri();
        String queuePath = path.endsWith("/") ? path.substring(0, path.length() - 1) : path;
        String queueUrl = reqUri.getScheme() + "://" + reqUri.getAuthority() + queuePath;

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
        rewriteToRoot(ctx);
    }

    private void rewriteToRoot(ContainerRequestContext ctx) {
        ctx.setRequestUri(ctx.getUriInfo().getRequestUriBuilder()
                .replacePath("/")
                .build());
    }

    private boolean isSqsTarget(String target) {
        return target != null && target.startsWith("AmazonSQS.");
    }
}
