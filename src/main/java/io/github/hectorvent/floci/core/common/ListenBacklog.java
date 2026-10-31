package io.github.hectorvent.floci.core.common;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * The accept backlog Floci's data-plane TCP listeners bind with.
 *
 * <p>A plain {@code ServerSocket} listens with a backlog of 50, socat with 5. A burst of
 * connections (a pool refilling, a service fanning out) overflows an accept queue that
 * small before the listener can drain it, and the kernel then silently drops the
 * handshake's final ACK: the client believes it is connected, sends its first message and
 * waits on retransmits, about a second for one drop, tens of seconds when the drops repeat,
 * and a reset or a hang for a connection dropped half-open.
 *
 * <p>The kernel caps the backlog at {@code net.core.somaxconn}, so a value beyond what the
 * host allows clamps the queue rather than failing the bind. The RDS, Redshift, ElastiCache
 * (with MemoryDB) and Neptune auth proxies pass this to their {@code ServerSocket} bind, and
 * the EC2 port-forward socat to its {@code backlog=} option.
 */
public final class ListenBacklog {

    /** The accept backlog of the database auth proxies and the EC2 port-forward socat. */
    public static final int ACCEPT = 4096;

    /**
     * Binds the accept socket for one of Floci's data-plane listeners: reuse-address on, a
     * bind at the given port with the given backlog, and the socket closed when the bind
     * fails so a caller's retry cannot leak one socket per attempt.
     */
    public static ServerSocket acceptSocket(int port, int backlog) throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port), backlog);
            return socket;
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // the socket was never bound, so nothing could have been listening on it
            }
            throw e;
        }
    }

    private ListenBacklog() {
    }
}
