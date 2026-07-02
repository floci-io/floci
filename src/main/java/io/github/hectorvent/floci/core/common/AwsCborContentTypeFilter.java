package io.github.hectorvent.floci.core.common;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

@Provider
@PreMatching
@Priority(Priorities.HEADER_DECORATOR)
public class AwsCborContentTypeFilter implements ContainerRequestFilter {

    static final String ORIGINAL_CONTENT_TYPE_HEADER = "X-Floci-Original-Content-Type";
    private static final String AWS_CBOR_1_1_MEDIA_TYPE = "application/x-amz-cbor-1.1";
    private static final String GENERIC_CBOR_MEDIA_TYPE = "application/cbor";

    @Override
    public void filter(ContainerRequestContext ctx) {
        String contentType = ctx.getHeaderString("Content-Type");
        if (contentType != null && contentType.startsWith(AWS_CBOR_1_1_MEDIA_TYPE)) {
            normalize(ctx, contentType);
            return;
        }

        // The Smithy-Protocol header is the definitive rpcv2Cbor claim signal;
        // normalize the content type so @Consumes matches even if it drifts
        // during a protocol transition.
        String smithyProtocol = ctx.getHeaderString(ProtocolClaimer.SMITHY_PROTOCOL_HEADER);
        boolean rpcV2Cbor = smithyProtocol != null
                && ProtocolClaimer.SMITHY_RPC_V2_CBOR.equals(smithyProtocol.trim());
        boolean alreadyGenericCbor = contentType != null && contentType.startsWith(GENERIC_CBOR_MEDIA_TYPE);
        if (rpcV2Cbor && !alreadyGenericCbor) {
            normalize(ctx, contentType);
        }
    }

    private void normalize(ContainerRequestContext ctx, String originalContentType) {
        if (originalContentType != null) {
            ctx.getHeaders().putSingle(ORIGINAL_CONTENT_TYPE_HEADER, originalContentType);
        }
        ctx.getHeaders().putSingle("Content-Type", GENERIC_CBOR_MEDIA_TYPE);
    }
}
