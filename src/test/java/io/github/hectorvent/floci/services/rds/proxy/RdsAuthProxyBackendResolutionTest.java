package io.github.hectorvent.floci.services.rds.proxy;

import io.github.hectorvent.floci.services.rds.container.RdsBackendGate;
import io.github.hectorvent.floci.services.rds.model.DatabaseEngine;
import io.github.hectorvent.floci.testutil.FreePorts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class RdsAuthProxyBackendResolutionTest {

    private final List<RdsAuthProxy> proxies = new ArrayList<>();
    private final List<ServerSocket> backends = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        for (RdsAuthProxy proxy : proxies) {
            proxy.stop();
        }
        for (ServerSocket backend : backends) {
            if (!backend.isClosed()) {
                backend.close();
            }
        }
    }

    @Test
    void keepsEachProxyConnectedToItsBackendWhenAddressesSwap() throws Exception {
        ServerSocket firstAddress = backendAt(0);
        ServerSocket secondAddress = backendAt(0);
        int firstPort = firstAddress.getLocalPort();
        int secondPort = secondAddress.getLocalPort();
        AtomicReference<RdsAuthProxy.BackendEndpoint> firstEndpoint = new AtomicReference<>(
                endpoint(firstPort));
        AtomicReference<RdsAuthProxy.BackendEndpoint> secondEndpoint = new AtomicReference<>(
                endpoint(secondPort));
        int[] proxyPorts = FreePorts.anyInterfacePorts(2);
        int firstProxyPort = proxyPorts[0];
        int secondProxyPort = proxyPorts[1];
        RdsAuthProxy firstProxy = proxy("db-a", firstPort, firstEndpoint::get);
        RdsAuthProxy secondProxy = proxy("db-b", secondPort, secondEndpoint::get);
        firstProxy.start(firstProxyPort);
        secondProxy.start(secondProxyPort);

        assertEquals('a', connectAndRead(firstProxyPort, firstAddress, 'a'));
        assertEquals('b', connectAndRead(secondProxyPort, secondAddress, 'b'));

        firstAddress.close();
        secondAddress.close();
        ServerSocket secondDatabaseAtFirstAddress = backendAt(firstPort);
        ServerSocket firstDatabaseAtSecondAddress = backendAt(secondPort);
        firstEndpoint.set(endpoint(secondPort));
        secondEndpoint.set(endpoint(firstPort));

        assertEquals('a', connectAndRead(
                firstProxyPort, firstDatabaseAtSecondAddress, 'a'));
        assertEquals('b', connectAndRead(
                secondProxyPort, secondDatabaseAtFirstAddress, 'b'));
    }

    @Test
    void usesLatestEndpointWhenPreviousAddressIsUnused() throws Exception {
        ServerSocket previousAddress = backendAt(0);
        int previousPort = previousAddress.getLocalPort();
        AtomicReference<RdsAuthProxy.BackendEndpoint> endpoint = new AtomicReference<>(
                endpoint(previousPort));
        int proxyPort = FreePorts.anyInterfacePort();
        RdsAuthProxy proxy = proxy("db-a", previousPort, endpoint::get);
        proxy.start(proxyPort);
        assertEquals('a', connectAndRead(proxyPort, previousAddress, 'a'));

        previousAddress.close();
        ServerSocket currentAddress = backendAt(0);
        endpoint.set(endpoint(currentAddress.getLocalPort()));

        assertEquals('a', connectAndRead(proxyPort, currentAddress, 'a'));
    }

    @Test
    void doesNotFallBackToStaleEndpointWhenResolutionFails() throws Exception {
        ServerSocket staleAddress = backendAt(0);
        staleAddress.setSoTimeout(500);
        int proxyPort = FreePorts.anyInterfacePort();
        RdsAuthProxy proxy = proxy("db-a", staleAddress.getLocalPort(),
                () -> {
                    throw new IOException("Docker inspect failed");
                });
        proxy.start(proxyPort);

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            client.setSoTimeout(5000);
            assertEquals(-1, client.getInputStream().read());
        }
        assertThrows(SocketTimeoutException.class, staleAddress::accept,
                "the proxy fell back to the stale backend address");
    }

    @Test
    void postgresReportsConnectionFailureWhenEndpointResolutionFails() throws Exception {
        int proxyPort = FreePorts.anyInterfacePort();
        RdsAuthProxy proxy = proxy("db-a", 1,
                () -> {
                    throw new IOException("Docker inspect failed");
                }, DatabaseEngine.POSTGRES);
        proxy.start(proxyPort);

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            client.setSoTimeout(5000);
            ByteArrayOutputStream startup = new ByteArrayOutputStream();
            DataOutputStream fields = new DataOutputStream(startup);
            fields.writeInt(196608);
            fields.writeBytes("user");
            fields.writeByte(0);
            fields.writeBytes("admin");
            fields.writeByte(0);
            fields.writeBytes("database");
            fields.writeByte(0);
            fields.writeBytes("app");
            fields.writeByte(0);
            fields.writeByte(0);
            DataOutputStream request = new DataOutputStream(client.getOutputStream());
            request.writeInt(startup.size() + 4);
            startup.writeTo(request);
            request.flush();

            DataInputStream response = new DataInputStream(client.getInputStream());
            assertEquals('R', response.readUnsignedByte());
            assertEquals(8, response.readInt());
            assertEquals(3, response.readInt());
            byte[] password = "Secret123\0".getBytes(StandardCharsets.UTF_8);
            request.writeByte('p');
            request.writeInt(password.length + 4);
            request.write(password);
            request.flush();

            assertEquals('E', response.readUnsignedByte());
            byte[] errorFields = response.readNBytes(response.readInt() - 4);
            String error = new String(errorFields, StandardCharsets.UTF_8);
            assertTrue(error.contains("C08006"));
            assertTrue(error.contains("Mcould not connect to backend database"));
        }
    }

    @Test
    void onlyADatabaseEndpointForwardsACancelRequest() throws Exception {
        assertTrue(cancelReachesBackend(RdsAuthProxy.EndpointKind.DATABASE));
        assertFalse(cancelReachesBackend(RdsAuthProxy.EndpointKind.STANDARD_DB_PROXY));
    }

    private boolean cancelReachesBackend(RdsAuthProxy.EndpointKind endpointKind) throws Exception {
        ServerSocket backend = backendAt(0);
        AtomicInteger resolved = new AtomicInteger();
        int proxyPort = FreePorts.anyInterfacePort();
        proxy("db-a", backend.getLocalPort(), () -> {
            resolved.incrementAndGet();
            return endpoint(backend.getLocalPort());
        }, DatabaseEngine.POSTGRES, endpointKind).start(proxyPort);

        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            client.setSoTimeout(5000);
            DataOutputStream request = new DataOutputStream(client.getOutputStream());
            request.writeInt(16);
            request.writeInt(80877102);
            request.writeInt(4242);
            request.writeInt(0x5eed);
            request.flush();
            // The proxy closes the client only after it has forwarded or dropped the cancel.
            assertEquals(-1, client.getInputStream().read());
        }
        return resolved.get() > 0;
    }

    private RdsAuthProxy proxy(String id, int initialBackendPort,
                               RdsAuthProxy.BackendEndpointResolver endpointResolver) {
        return proxy(id, initialBackendPort, endpointResolver, DatabaseEngine.SQLSERVER);
    }

    private RdsAuthProxy proxy(String id, int initialBackendPort,
                               RdsAuthProxy.BackendEndpointResolver endpointResolver,
                               DatabaseEngine engine) {
        return proxy(id, initialBackendPort, endpointResolver, engine, RdsAuthProxy.EndpointKind.DATABASE);
    }

    private RdsAuthProxy proxy(String id, int initialBackendPort,
                               RdsAuthProxy.BackendEndpointResolver endpointResolver,
                               DatabaseEngine engine, RdsAuthProxy.EndpointKind endpointKind) {
        RdsAuthProxy proxy = new RdsAuthProxy(
                id, "127.0.0.1", initialBackendPort, endpointResolver, "runtime:" + id,
                engine, false, "sa", "Secret123", null,
                mock(RdsSigV4Validator.class), mock(RdsProxyTlsCertificates.class),
                (user, password) -> true, 5000, 5000, 100, null,
                username -> false, RdsBackendGate.OPEN, endpointKind);
        proxies.add(proxy);
        return proxy;
    }

    private ServerSocket backendAt(int port) throws IOException {
        ServerSocket backend = new ServerSocket();
        backend.setReuseAddress(true);
        backend.bind(new InetSocketAddress("127.0.0.1", port));
        backends.add(backend);
        return backend;
    }

    private static RdsAuthProxy.BackendEndpoint endpoint(int port) {
        return new RdsAuthProxy.BackendEndpoint("127.0.0.1", port);
    }

    private static int connectAndRead(int proxyPort, ServerSocket backend, int marker)
            throws Exception {
        Thread serving = Thread.ofVirtual().start(() -> {
            try (Socket accepted = backend.accept()) {
                accepted.getOutputStream().write(marker);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        int received;
        try (Socket client = new Socket("127.0.0.1", proxyPort)) {
            client.setSoTimeout(5000);
            received = client.getInputStream().read();
        }
        assertTrue(serving.join(Duration.ofSeconds(5)), "fake backend did not finish serving");
        return received;
    }
}
