package io.github.hectorvent.floci.core.common;

import jakarta.ws.rs.container.ContainerRequestContext;

/**
 * The non-payload identification signals a request's wire protocol is claimed
 * from: HTTP method, path, and the claim-relevant headers. The entity stream
 * is deliberately absent — claiming must never touch it.
 *
 * @param method         the HTTP method
 * @param path           the request path, after any pre-matching rewrites
 * @param contentType    the raw Content-Type header, preferring the original
 *                       value stashed by {@link AwsCborContentTypeFilter}
 * @param smithyProtocol the raw Smithy-Protocol header, or null
 * @param xAmzTarget     the raw X-Amz-Target header, or null
 * @param authorization  the raw Authorization header, or null
 */
public record ClaimSignals(String method, String path, String contentType,
                           String smithyProtocol, String xAmzTarget, String authorization) {

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
                ctx.getHeaderString("Authorization"));
    }
}