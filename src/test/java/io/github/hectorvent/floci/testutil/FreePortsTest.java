package io.github.hectorvent.floci.testutil;

import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FreePortsTest {

    @Test
    void anyInterfacePorts_returnsDistinctPorts() throws Exception {
        int[] ports = FreePorts.anyInterfacePorts(32);

        assertEquals(32, ports.length);
        assertEquals(32, Arrays.stream(ports).distinct().count());
    }

    @Test
    void anyInterfacePorts_releasesEveryPort() throws Exception {
        for (int port : FreePorts.anyInterfacePorts(4)) {
            try (ServerSocket socket = new ServerSocket(port)) {
                assertEquals(port, socket.getLocalPort());
            }
        }
    }

    @Test
    void anyInterfacePorts_rejectsCountBelowOne() {
        assertThrows(IllegalArgumentException.class, () -> FreePorts.anyInterfacePorts(0));
    }
}
