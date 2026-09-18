package io.github.hectorvent.floci.services.rds.proxy;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TcpStreamBridgeTest {

    @Test
    void relaysBytesInBothDirections() throws Exception {
        try (ServerSocket listener = new ServerSocket(0);
             Socket client = new Socket("127.0.0.1", listener.getLocalPort());
             Socket backend = listener.accept()) {
            Thread relay = Thread.ofVirtual().start(() -> TcpStreamBridge.relay(client, backend));
            OutputStream clientOutput = client.getOutputStream();
            InputStream clientInput = client.getInputStream();
            OutputStream backendOutput = backend.getOutputStream();
            InputStream backendInput = backend.getInputStream();

            clientOutput.write("client-to-server".getBytes(StandardCharsets.UTF_8));
            clientOutput.flush();
            byte[] fromClient = backendInput.readNBytes(16);
            backendOutput.write("server-to-client".getBytes(StandardCharsets.UTF_8));
            backendOutput.flush();
            byte[] fromBackend = clientInput.readNBytes(16);

            assertEquals("client-to-server", new String(fromClient, StandardCharsets.UTF_8));
            assertEquals("server-to-client", new String(fromBackend, StandardCharsets.UTF_8));
            client.shutdownOutput();
            backend.shutdownOutput();
            relay.join();
        }
    }
}
