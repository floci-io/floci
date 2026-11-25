package io.floci.aws.services.ssmmessages;

import io.vertx.ext.web.RoutingContext;

public interface DataChannelHandler {

    /** Checks whether this handler owns a live session without claiming it. */
    boolean supportsSession(String sessionId);

    /** Handles the upgrade request and the channel's authentication and handshake. */
    void open(String sessionId, RoutingContext context);
}
