package io.github.hectorvent.floci.core.common;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Rejects a query request that {@link SqsQueueUrlRouterFilter} found signed for SQS on a /queue/{region}/ path with
 * 404 InvalidAddress, as AWS rejects it. It runs right after {@link SigV4HeaderSignatureFilter}, so when signatures
 * are validated a request with a bad signature gets the signature error instead.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 1)
public class SqsInvalidAddressFilter implements ContainerRequestFilter {

    @Override
    public void filter(ContainerRequestContext ctx) {
        if (ctx.getProperty(SqsQueueUrlRouterFilter.INVALID_ADDRESS_PROPERTY) != null) {
            ctx.abortWith(AwsQueryResponse.error("InvalidAddress", "The specified ID is invalid.",
                    AwsNamespaces.SQS, 404));
        }
    }
}
