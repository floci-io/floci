package io.github.hectorvent.floci.testutil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;

/** Helpers for choosing ports in tests that bind their own servers. */
public final class FreePorts {

    private FreePorts() {
    }

    /** Returns a port selected by binding on any local interface. */
    public static int anyInterfacePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /**
     * Returns {@code count} distinct ports selected by binding on any local interface.
     * Every socket stays open until all ports are chosen, so no port is returned twice. Calling
     * {@link #anyInterfacePort()} several times in a row can return the same port twice, and a
     * Vert.x instance then silently shares that port between its servers instead of failing the
     * second bind.
     */
    public static int[] anyInterfacePorts(int count) throws IOException {
        if (count < 1) {
            throw new IllegalArgumentException("count must be at least 1, was " + count);
        }
        List<ServerSocket> sockets = new ArrayList<>();
        try {
            int[] ports = new int[count];
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0);
                sockets.add(socket);
                ports[i] = socket.getLocalPort();
            }
            return ports;
        } finally {
            closeAll(sockets);
        }
    }

    /**
     * Returns a port selected by binding to IPv4 loopback, for host-side Docker bindings.
     * Docker Desktop needs this host port named explicitly: a daemon-picked host port is allocated
     * on the host side only, where the daemon that pushes and pulls cannot reach it.
     */
    public static int loopbackIpv4Port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }

    private static void closeAll(List<ServerSocket> sockets) throws IOException {
        IOException failure = null;
        for (ServerSocket socket : sockets) {
            try {
                socket.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }
}
