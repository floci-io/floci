package io.github.hectorvent.floci.config;

import io.quarkus.vertx.http.HttpServerOptionsCustomizer;
import io.vertx.core.http.HttpServerOptions;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Customizes Vert.x HTTP server options for Floci's requirements.
 *
 * <ul>
 *   <li>Removes the 8 KB per-attribute limit imposed by Vert.x's default
 *       {@code HttpServerOptions.maxFormAttributeSize}. Without this, any request
 *       with {@code Content-Type: application/x-www-form-urlencoded} and a single
 *       attribute value larger than ~8 KB is rejected by Netty's form decoder.
 *       This hits real AWS APIs that use the Query Protocol: CloudFormation templates,
 *       EC2 UserData (base64-encoded), large IAM policies, etc.</li>
 *   <li>Raises the WebSocket frame and message size limits to accommodate payloads
 *       up to 128 KB, matching the AWS API Gateway WebSocket payload limit. Vert.x
 *       defaults to 64 KB per frame, which causes Netty to silently reject larger
 *       frames before the application-level size check in
 *       {@code WebSocketHandler} can fire.</li>
 * </ul>
 *
 * The overall request body size is still bounded by
 * {@code quarkus.http.limits.max-body-size} (default 2048 MB).
 */
@ApplicationScoped
public class HttpOptionsCustomizer implements HttpServerOptionsCustomizer {

    /**
     * Maximum WebSocket frame size: 256 KB.
     * AWS API Gateway allows up to 128 KB payloads. We set the transport limit higher
     * so that oversized frames (e.g. 128 KB + 1 byte) still reach the application-level
     * handler in {@code WebSocketHandler}, which sends back a proper error response.
     */
    private static final int MAX_WS_FRAME_SIZE = 256 * 1024;

    /**
     * Maximum WebSocket message size (reassembled from multiple frames).
     * Set to the same value as the frame limit.
     */
    private static final int MAX_WS_MESSAGE_SIZE = 256 * 1024;

    @Override
    public void customizeHttpServer(HttpServerOptions options) {
        options.setMaxFormAttributeSize(-1);
        options.setMaxWebSocketFrameSize(MAX_WS_FRAME_SIZE);
        options.setMaxWebSocketMessageSize(MAX_WS_MESSAGE_SIZE);
    }

    @Override
    public void customizeHttpsServer(HttpServerOptions options) {
        options.setMaxFormAttributeSize(-1);
        options.setMaxWebSocketFrameSize(MAX_WS_FRAME_SIZE);
        options.setMaxWebSocketMessageSize(MAX_WS_MESSAGE_SIZE);
    }
}
