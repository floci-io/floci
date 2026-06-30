package io.github.hectorvent.floci.services.apigatewayv2.websocket;

import io.github.hectorvent.floci.core.common.Resettable;
import io.vertx.core.http.ServerWebSocket;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory manager for active WebSocket connections.
 * Tracks connection metadata and live socket references.
 */
@ApplicationScoped
public class WebSocketConnectionManager implements Resettable {

    private static final Logger LOG = Logger.getLogger(WebSocketConnectionManager.class);

    private final ConcurrentHashMap<String, ConnectionInfo> connections = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ServerWebSocket> sockets = new ConcurrentHashMap<>();

    /**
     * Tracks connections that were closed server-side via the @connections DELETE API.
     * When a connection is in this set, the $disconnect Lambda should NOT be invoked
     * because AWS does not invoke $disconnect for server-initiated disconnections via
     * the management API.
     */
    private final Set<String> serverInitiatedCloses = ConcurrentHashMap.newKeySet();

    /**
     * Register a new WebSocket connection.
     */
    public void register(String connectionId, ConnectionInfo info, ServerWebSocket ws) {
        connections.put(connectionId, info);
        sockets.put(connectionId, ws);
        LOG.debugv("Registered WebSocket connection {0}", connectionId);
    }

    /**
     * Unregister a WebSocket connection, removing both metadata and socket reference.
     */
    public void unregister(String connectionId) {
        connections.remove(connectionId);
        sockets.remove(connectionId);
        serverInitiatedCloses.remove(connectionId);
        LOG.debugv("Unregistered WebSocket connection {0}", connectionId);
    }

    public void clear() {
        sockets.values().forEach(ws -> {
            try {
                ws.close();
            } catch (Exception ignored) {}
        });
        connections.clear();
        sockets.clear();
        serverInitiatedCloses.clear();
    }

    /**
     * Send a text message to the specified connection.
     *
     * @throws IllegalStateException if the connection is not active
     */
    public void sendMessage(String connectionId, String message) {
        ServerWebSocket ws = sockets.get(connectionId);
        if (ws == null) {
            throw new IllegalStateException("Connection " + connectionId + " is not active");
        }
        try {
            ws.writeTextMessage(message);
        } catch (Exception e) {
            LOG.debugv("Failed to write message to connection {0}: {1}", connectionId, e.getMessage());
            throw new IllegalStateException("Connection " + connectionId + " is not active");
        }
    }

    /**
     * Close the specified connection via the @connections DELETE API.
     * Marks the connection as server-initiated so that the $disconnect Lambda
     * is NOT invoked when the close handler fires (matching AWS behavior).
     *
     * @throws IllegalStateException if the connection is not active
     */
    public void closeConnection(String connectionId) {
        ServerWebSocket ws = sockets.get(connectionId);
        if (ws == null) {
            throw new IllegalStateException("Connection " + connectionId + " is not active");
        }
        // Mark as server-initiated BEFORE closing so the close handler knows to skip $disconnect
        serverInitiatedCloses.add(connectionId);
        ws.close();
        // Note: unregister is called by the close handler in WebSocketHandler.onClose()
    }

    /**
     * Check whether a close was initiated by the server (via @connections DELETE API).
     * When true, the $disconnect Lambda should NOT be invoked.
     */
    public boolean isServerInitiatedClose(String connectionId) {
        return serverInitiatedCloses.contains(connectionId);
    }

    /**
     * Get connection metadata for the specified connection.
     *
     * @return the ConnectionInfo, or null if the connection is not active
     */
    public ConnectionInfo getConnectionInfo(String connectionId) {
        return connections.get(connectionId);
    }

    /**
     * Update the lastActiveAt timestamp on the connection metadata.
     */
    public void updateLastActiveAt(String connectionId) {
        ConnectionInfo info = connections.get(connectionId);
        if (info != null) {
            info.setLastActiveAt(System.currentTimeMillis());
        }
    }

    /**
     * Check whether a connection is currently active.
     */
    public boolean isConnected(String connectionId) {
        return sockets.containsKey(connectionId);
    }
}
