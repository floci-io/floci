package io.github.hectorvent.floci.core.common;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Decompresses a gzip-encoded JSON 1.0 {@code PutMetricData} body for {@link AwsJsonController}.
 * It is the one operation the AWS CLI and the SDKs that call CloudWatch over JSON compress, which
 * by default they do for any body of 10 KiB or more.
 *
 * <p>An entity interceptor runs when the resource method reads its body, after every request
 * filter. By then {@link SigV4HeaderSignatureFilter} has verified the signature over the
 * compressed bytes as sent, which are the bytes the client signed, and {@link IamEnforcementFilter}
 * has decided the request from its {@code X-Amz-Target}. Deciding before decompression is sound
 * only because nothing in the {@code PutMetricData} decision comes from the body: its resource is
 * {@code *}. A condition key read from the body, such as {@code cloudwatch:namespace}, would need
 * the body decompressed before IAM.
 *
 * <p>The decompressed body is bounded as the CBOR protocol bounds it
 * ({@link AwsJsonCborController#decodeBody}), and a body that is too large or cannot be
 * decompressed is refused in the controller's JSON error format. Every other request reads its
 * body as sent.
 */
@Provider
@CloudWatchGzipBody
@Priority(Priorities.ENTITY_CODER)
public class CloudWatchGzipReaderInterceptor implements ReaderInterceptor {

    private static final MediaType AWS_JSON_1_0 =
            MediaType.valueOf(AwsJsonController.CONTENT_TYPE_AWS_JSON_1_0);

    private final ResolvedServiceCatalog catalog;

    @Inject
    public CloudWatchGzipReaderInterceptor(ResolvedServiceCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
        MultivaluedMap<String, String> headers = context.getHeaders();
        if (!AwsJsonCborController.isGZipped(headers.get(HttpHeaders.CONTENT_ENCODING))
                || context.getMediaType() == null || !context.getMediaType().isCompatible(AWS_JSON_1_0)
                || !isMetricPublication(headers.getFirst("X-Amz-Target"))) {
            return context.proceed();
        }
        byte[] body = context.getInputStream().readAllBytes();
        // No payload is an operation without input, as on the CBOR protocol, not a malformed gzip stream.
        if (body.length > 0) {
            try {
                body = AwsJsonCborController.decodeBody(body);
            } catch (AwsException e) {
                throw new WebApplicationException(JsonErrorResponseUtils.createErrorResponse(e));
            }
        }
        context.setInputStream(new ByteArrayInputStream(body));
        return context.proceed();
    }

    private boolean isMetricPublication(String target) {
        return catalog.matchTarget(target)
                .filter(match -> "monitoring".equals(match.descriptor().externalKey()))
                .map(ServiceCatalog.TargetMatch::action)
                // CloudWatchMetricsJsonHandler runs putMetricData as PutMetricData too.
                .filter(action -> "PutMetricData".equals(action) || "putMetricData".equals(action))
                .isPresent();
    }
}
