package io.github.hectorvent.floci.testutil;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

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

    /** Returns a port selected by binding to IPv4 loopback, for host-side Docker bindings. */
    public static int loopbackIpv4Port() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            return socket.getLocalPort();
        }
    }
}
