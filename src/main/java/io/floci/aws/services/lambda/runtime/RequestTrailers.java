package io.floci.aws.services.lambda.runtime;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.util.AttributeKey;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.net.impl.ConnectionBase;

/**
 * The HTTP trailers of a request. Vert.x exposes none on a server request, but Netty's decoder
 * delivers them with the last content, so this reads them off the connection's pipeline.
 * {@code ConnectionBase} is internal to Vert.x and is used only to reach that pipeline.
 */
final class RequestTrailers {

    private static final AttributeKey<HttpHeaders> TRAILERS = AttributeKey.valueOf("floci.request-trailers");

    private RequestTrailers() {
    }

    /** Starts recording the trailers of every request that arrives on {@code connection}. */
    static void install(HttpConnection connection) {
        if (connection instanceof ConnectionBase base) {
            ChannelHandlerContext vertxHandler = base.channelHandlerContext();
            vertxHandler.pipeline().addBefore(vertxHandler.name(), "floci-request-trailers", new Recorder());
        }
    }

    /** The trailers of the request that just ended on {@code connection}, or null when it had none. */
    static HttpHeaders take(HttpConnection connection) {
        if (connection instanceof ConnectionBase base) {
            return base.channel().attr(TRAILERS).getAndSet(null);
        }
        return null;
    }

    private static final class Recorder extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof LastHttpContent last) {
                HttpHeaders trailers = last.trailingHeaders();
                ctx.channel().attr(TRAILERS).set(trailers.isEmpty() ? null : trailers);
            }
            ctx.fireChannelRead(msg);
        }
    }
}
