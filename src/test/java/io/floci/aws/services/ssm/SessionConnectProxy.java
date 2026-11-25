package io.floci.aws.services.ssm;

import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Interrupts only the test client's transport, leaving the agent and shell running. */
final class SessionConnectProxy implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(SessionConnectProxy.class);
    private final ServerSocket listener;
    private final URI endpoint;
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private final Set<Socket> tunnels = ConcurrentHashMap.newKeySet();
    private final AtomicInteger connections = new AtomicInteger();

    SessionConnectProxy(URI endpoint) throws IOException {
        this.endpoint = endpoint;
        listener = new ServerSocket(0, 50, InetAddress.getByName(endpoint.getHost()));
        Thread.ofVirtual().start(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    sockets.add(client);
                    Thread.ofVirtual().start(() -> forward(client));
                } catch (IOException e) {
                    if (!listener.isClosed()) {
                        LOG.warnv(e, "Session test proxy failed to accept a connection");
                    }
                }
            }
        });
    }

    int port() {
        return listener.getLocalPort();
    }

    int connections() {
        return connections.get();
    }

    void disconnect() throws IOException {
        for (Socket socket : tunnels) {
            socket.close();
        }
    }

    private void forward(Socket client) {
        try (client; Socket remote = new Socket()) {
            client.setSoTimeout(10000);
            String header = header(client.getInputStream());
            String[] request = header.substring(0, header.indexOf("\r\n")).split(" ", 3);
            boolean connect = "CONNECT".equals(request[0]);
            URI destination = URI.create(connect ? "http://" + request[1] : request[1]);
            if (!endpoint.getHost().equals(destination.getHost()) || endpoint.getPort() != destination.getPort()) {
                throw new IOException("Session test proxy only forwards to its Floci endpoint");
            }
            remote.connect(new InetSocketAddress(destination.getHost(), destination.getPort()), 5000);
            sockets.add(remote);
            if (connect) {
                tunnels.add(client);
                tunnels.add(remote);
                connections.incrementAndGet();
                client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n"
                        .getBytes(StandardCharsets.US_ASCII));
                client.getOutputStream().flush();
            } else {
                String path = destination.getRawPath();
                if (destination.getRawQuery() != null) {
                    path += "?" + destination.getRawQuery();
                }
                remote.getOutputStream().write((request[0] + " " + path + " " + request[2]
                        + header.substring(header.indexOf("\r\n"))).getBytes(StandardCharsets.US_ASCII));
            }
            client.setSoTimeout(0);
            Thread upstream = Thread.ofVirtual().start(() -> pipe(client, remote));
            pipe(remote, client);
            upstream.join(1000);
            sockets.remove(remote);
            tunnels.remove(remote);
        } catch (IOException e) {
            LOG.debugv(e, "Session test proxy connection closed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            sockets.remove(client);
            tunnels.remove(client);
        }
    }

    private static String header(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int tail = 0;
        while (bytes.size() < 65536) {
            int value = input.read();
            if (value < 0) {
                throw new IOException("Proxy request ended before its headers");
            }
            bytes.write(value);
            tail = (tail << 8) | value;
            if (tail == 0x0d0a0d0a) {
                return bytes.toString(StandardCharsets.US_ASCII);
            }
        }
        throw new IOException("Proxy request headers exceed 64 KiB");
    }

    private static void pipe(Socket source, Socket destination) {
        try {
            source.getInputStream().transferTo(destination.getOutputStream());
            destination.shutdownOutput();
        } catch (IOException e) {
            LOG.debugv(e, "Session test proxy stream interrupted");
        }
    }

    @Override
    public void close() throws IOException {
        listener.close();
        for (Socket socket : sockets) {
            socket.close();
        }
    }
}
