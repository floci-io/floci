package io.floci.aws.core.common.docker;

import io.floci.aws.core.common.ListenBacklog;

/**
 * The two socat addresses of a TCP relay container: a listener that forks a child for each accepted connection, and
 * the address each child connects its client to.
 *
 * <p>Both addresses run with {@code nodelay} (TCP_NODELAY). With Nagle's algorithm left on, socat holds a client's
 * second small write until the first is acknowledged, and a peer with delayed ACK stretches that to roughly 40 ms on
 * the Docker network, so a request the client sends as two writes pays a visible stall on every round trip.
 *
 * <p>The listener also sets {@code backlog} to {@link ListenBacklog#ACCEPT}, since socat's default accept queue of 5
 * silently drops the final ACK of a burst's overflow connections, which the client then pays for in retransmit stalls
 * or a reset.
 */
public final class SocatTcpRelay {

    private static final String LISTENER_OPTIONS = ",fork,reuseaddr,nodelay,backlog=" + ListenBacklog.ACCEPT;

    /** Listens on {@code port} on every IPv4 address the container holds. */
    public static String listen(int port) {
        return "TCP-LISTEN:" + port + LISTENER_OPTIONS;
    }

    /** Listens on {@code port} on the IPv4 {@code address} alone. */
    public static String listen(int port, String address) {
        return "TCP-LISTEN:" + port + ",bind=" + address + LISTENER_OPTIONS;
    }

    /** Listens on {@code port} for IPv4 and IPv6 clients alike. */
    public static String listenDualStack(int port) {
        return "TCP6-LISTEN:" + port + ",ipv6only=0" + LISTENER_OPTIONS;
    }

    /** Connects to {@code host}, a name or an IPv4 address, on {@code port}. */
    public static String connect(String host, int port) {
        return "TCP:" + host + ":" + port + ",nodelay";
    }

    /** Connects to the IPv6 {@code address} on {@code port}. */
    public static String connectIpv6(String address, int port) {
        return "TCP6:[" + address + "]:" + port + ",nodelay";
    }

    private SocatTcpRelay() {
    }
}
