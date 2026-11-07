package io.floci.aws.core.common;

import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Closes the connection after answering a request that sent {@code Expect: 100-continue} before any
 * of its body arrived, as when an upload is rejected on its headers alone. The client never sends a
 * body it was not told to continue with, so a connection kept open would read the client's next
 * request as that body. The response says {@code Connection: close}, so the client does not reuse it
 * either.
 */
@Provider
public class ExpectContinueConnectionFilter implements ContainerResponseFilter {

    private final CurrentVertxRequest currentVertxRequest;

    public ExpectContinueConnectionFilter(CurrentVertxRequest currentVertxRequest) {
        this.currentVertxRequest = currentVertxRequest;
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        RoutingContext context = currentVertxRequest.getCurrent();
        if (context == null || !awaitsUnsentBody(context.request())) {
            return;
        }
        responseContext.getHeaders().putSingle(HttpHeaders.CONNECTION.toString(), HttpHeaders.CLOSE.toString());
        context.addEndHandler(ended -> context.request().connection().close());
    }

    /**
     * Whether the request is waiting to be told to continue before it sends its body. An HTTP/2
     * connection carries other requests, so it is never closed for one of them.
     */
    private static boolean awaitsUnsentBody(HttpServerRequest request) {
        return request.version() != HttpVersion.HTTP_2
                && request.headers().contains(HttpHeaders.EXPECT, HttpHeaders.CONTINUE, true)
                && !request.isEnded()
                && request.bytesRead() == 0;
    }
}
