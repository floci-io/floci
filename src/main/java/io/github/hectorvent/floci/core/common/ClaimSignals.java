package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedMap;

/**
 * The non-payload identification signals a request's wire protocol is claimed
 * from: HTTP method, path, query parameters, and claim-relevant headers. The entity stream
 * is deliberately absent — claiming must never touch it.
 *
 * @param method         the HTTP method
 * @param path           the request path, after any pre-matching rewrites
 * @param contentType    the raw Content-Type header, preferring the original
 *                       value stashed by {@link AwsCborContentTypeFilter}
 * @param smithyProtocol the raw Smithy-Protocol header, or null
 * @param xAmzTarget     the raw X-Amz-Target header, or null
 * @param authorization  the raw Authorization header, or null
 * @param accept         the raw Accept header, or null
 * @param queryParameters the request query parameters
 */
public record ClaimSignals(String method, String path, String contentType,
                           String smithyProtocol, String xAmzTarget, String authorization,
                           String accept, MultivaluedMap<String, String> queryParameters) {

    static ClaimSignals from(ContainerRequestContext ctx) {
        String contentType = ctx.getHeaderString(AwsCborContentTypeFilter.ORIGINAL_CONTENT_TYPE_HEADER);
        if (contentType == null) {
            contentType = ctx.getHeaderString("Content-Type");
        }
        return new ClaimSignals(
                ctx.getMethod(),
                ctx.getUriInfo().getPath(),
                contentType,
                ctx.getHeaderString(ProtocolClaimer.SMITHY_PROTOCOL_HEADER),
                ctx.getHeaderString("X-Amz-Target"),
                ctx.getHeaderString("Authorization"),
                ctx.getHeaderString("Accept"),
                ctx.getUriInfo().getQueryParameters());
    }
}
