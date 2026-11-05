package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adds AWS request-id response headers to every HTTP response.
 *
 * <p>Real AWS services always return a request identifier so that SDKs can
 * populate {@code $metadata.requestId}.  The header name varies by protocol:
 * <ul>
 *   <li>{@code x-amz-request-id} — REST XML (S3), REST JSON (Lambda), Query protocol</li>
 *   <li>{@code x-amzn-RequestId}  — JSON 1.0 / 1.1 services (DynamoDB, SSM, …)</li>
 *   <li>{@code x-amz-id-2}        — S3 extended request ID</li>
 * </ul>
 *
 * <p>This filter emits all three so that every AWS SDK variant can find the
 * header it expects.  If a controller already set {@code x-amz-request-id}
 * (e.g. Lambda invoke) or {@code x-amzn-RequestId}, that value is preserved and
 * repeated in the other headers, so every SDK reads the same ID.
 */
@Provider
public class AwsRequestIdFilter implements ContainerResponseFilter {

    private static final String AMZ_REQUEST_ID = "x-amz-request-id";
    private static final String AMZN_REQUEST_ID = "x-amzn-RequestId";
    private static final String AMZ_ID_2 = "x-amz-id-2";

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        MultivaluedMap<String, Object> headers = responseContext.getHeaders();

        // Reuse the same ID across all header variants for this response
        String requestId = existingRequestId(headers);

        if (!headers.containsKey(AMZ_REQUEST_ID)) {
            headers.putSingle(AMZ_REQUEST_ID, requestId);
        }
        if (!headers.containsKey(AMZN_REQUEST_ID)) {
            headers.putSingle(AMZN_REQUEST_ID, requestId);
        }
        if (!headers.containsKey(AMZ_ID_2)) {
            headers.putSingle(AMZ_ID_2, requestId);
        }
    }

    private static String existingRequestId(MultivaluedMap<String, Object> headers) {
        for (String name : new String[] {AMZ_REQUEST_ID, AMZN_REQUEST_ID}) {
            Object value = headers.getFirst(name);
            if (value != null) {
                return value.toString();
            }
        }
        return newRequestId();
    }

    // A request id is not a secret, so it does not need SecureRandom.
    private static String newRequestId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        long high = (random.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        long low = (random.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(high, low).toString();
    }
}
