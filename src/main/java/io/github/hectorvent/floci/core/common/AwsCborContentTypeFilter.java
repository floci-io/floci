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
        if (contentType == null || !contentType.startsWith(AWS_CBOR_1_1_MEDIA_TYPE)) {
            return;
        }

        ctx.getHeaders().putSingle(ORIGINAL_CONTENT_TYPE_HEADER, contentType);
        ctx.getHeaders().putSingle("Content-Type", GENERIC_CBOR_MEDIA_TYPE);
    }
}
