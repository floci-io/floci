package io.github.hectorvent.floci.services.elasticache.proxy;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.elasticache.model.AuthMode;
import io.github.hectorvent.floci.services.elasticache.model.TransitEncryption;
import io.github.hectorvent.floci.testing.TlsTrust;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ElastiCacheTlsProxyTest {
    private static final byte[] AUTH = "*2\r\n$4\r\nAUTH\r\n$18\r\nsynthetic-password\r\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] PING = "*1\r\n$4\r\nPING\r\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path directory;

    private ElastiCacheAuthProxy proxy(FlociCertificateAuthority authority, int backendPort) {
        return proxy(authority, backendPort, () -> TransitEncryption.REQUIRED);
    }

    private ElastiCacheAuthProxy proxy(FlociCertificateAuthority authority, int backendPort,
                                       Supplier<TransitEncryption> encryption) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.hostname()).thenReturn(Optional.of("cache.test"));
        when(config.baseUrl()).thenReturn("https://base.test:4566");
        when(config.services().elasticache().clusterAnnounceHostname()).thenReturn(Optional.of("nodes.test"));
        ElastiCacheTlsCertificates certificates = new ElastiCacheTlsCertificates(authority, config, new CertificateGenerator());
        return new ElastiCacheAuthProxy("tls-test", AuthMode.PASSWORD, "127.0.0.1", backendPort,
                new ElastiCacheAuthProxy.PasswordValidator() {
                    @Override
                    public boolean validatePassword(String username, String password) {
                        return "synthetic-password".equals(password);
                    }

                    @Override
                    public TransitEncryption transitEncryption() {
                        return encryption.get();
                    }
                }, mock(SigV4Validator.class), certificates::sslContext);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cache.test", "nodes.test", "base.test", "host.docker.internal",
            "localhost", "127.0.0.1", "0.0.0.0", "::1", "localhost.floci.io"})
    void tlsVerifiesTheSharedCaAndEndpointNameThenAuthenticatesAndRelays(String hostname) throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        try (ServerSocket backend = new ServerSocket(0)) {
            CompletableFuture<String> received = CompletableFuture.supplyAsync(() -> {
                try (Socket socket = backend.accept()) {
                    socket.setSoTimeout(5000);
                    String[] command = new RespReader(socket.getInputStream()).readCommand();
                    socket.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                    return command[0];
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            ElastiCacheAuthProxy proxy = proxy(authority, backend.getLocalPort());
            proxy.start(0);
            try (Socket client = tlsClient(authority, proxy.localPort(), hostname)) {
                BufferedReader reader = reader(client);
                assertEquals("+OK", send(client, reader, AUTH));
                assertEquals("+PONG", send(client, reader, PING));
                assertEquals("PING", received.get(5, TimeUnit.SECONDS));
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void encryptedEndpointRejectsPlaintextBeforeBackendAccess() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        try (ServerSocketChannel backend = idleBackend()) {
            ElastiCacheAuthProxy proxy = proxy(authority, backendPort(backend));
            proxy.start(0);
            try (Socket client = plaintextClient(proxy.localPort())) {
                client.getOutputStream().write(PING);
                client.getOutputStream().flush();
                // A fatal TLS alert or EOF is acceptable; no RESP success can be returned.
                int first = client.getInputStream().read();
                assertTrue(first == -1 || first == 21);
                awaitProxyClose(client);
                assertNull(backend.accept(), "a refused client must not reach the backend");
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void rejectsAnUntrustedCaAndAnIncorrectEndpointName() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory.resolve("server"));
        FlociCertificateAuthority wrong = FlociCertificateAuthority.loadOrCreate(directory.resolve("untrusted"));
        try (ServerSocketChannel backend = idleBackend()) {
            ElastiCacheAuthProxy proxy = proxy(authority, backendPort(backend));
            proxy.start(0);
            int port = proxy.localPort();
            try {
                for (boolean wrongName : new boolean[]{false, true}) {
                    SSLContext context = TlsTrust.trusting((wrongName ? authority : wrong).certificate());
                    // autoClose off: tcp stays open after the refusal, so the proxy's close can be seen
                    try (Socket tcp = plaintextClient(port);
                            SSLSocket client = (SSLSocket) context.getSocketFactory().createSocket(tcp,
                                    wrongName ? "wrong.test" : "cache.test", port, false)) {
                        verifyEndpointName(client);
                        assertThrows(IOException.class, client::startHandshake);
                        awaitProxyClose(tcp);
                    }
                }
                assertNull(backend.accept(), "a refused client must not reach the backend");
            } finally {
                proxy.stop();
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void preferredModeTakesEncryptedAndUnencryptedClients(boolean encrypted) throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        try (ServerSocket backend = new ServerSocket(0)) {
            answerEveryCommandWithPong(backend);
            ElastiCacheAuthProxy proxy = proxy(authority, backend.getLocalPort(), () -> TransitEncryption.PREFERRED);
            proxy.start(0);
            try (Socket client = encrypted
                    ? tlsClient(authority, proxy.localPort(), "cache.test")
                    : plaintextClient(proxy.localPort())) {
                BufferedReader reader = reader(client);
                assertEquals("+OK", send(client, reader, AUTH));
                assertEquals("+PONG", send(client, reader, PING));
            } finally {
                proxy.stop();
            }
        }
    }

    @Test
    void requiredModeDropsUnencryptedConnectionsAndKeepsEncryptedOnes() throws Exception {
        FlociCertificateAuthority authority = FlociCertificateAuthority.loadOrCreate(directory);
        AtomicReference<TransitEncryption> mode = new AtomicReference<>(TransitEncryption.PREFERRED);
        try (ServerSocket backend = new ServerSocket(0)) {
            answerEveryCommandWithPong(backend);
            ElastiCacheAuthProxy proxy = proxy(authority, backend.getLocalPort(), mode::get);
            proxy.start(0);
            try (Socket plaintext = plaintextClient(proxy.localPort());
                    Socket encrypted = tlsClient(authority, proxy.localPort(), "cache.test")) {
                BufferedReader plaintextReader = reader(plaintext);
                BufferedReader encryptedReader = reader(encrypted);
                assertEquals("+OK", send(plaintext, plaintextReader, AUTH));
                assertEquals("+PONG", send(plaintext, plaintextReader, PING));
                assertEquals("+OK", send(encrypted, encryptedReader, AUTH));
                assertEquals("+PONG", send(encrypted, encryptedReader, PING));

                mode.set(TransitEncryption.REQUIRED);
                proxy.dropUnencryptedConnections();

                assertNull(plaintextReader.readLine(), "the unencrypted connection must be closed");
                assertEquals("+PONG", send(encrypted, encryptedReader, PING));
            } finally {
                proxy.stop();
            }
        }
    }

    private static SSLSocket tlsClient(FlociCertificateAuthority authority, int port, String hostname) throws Exception {
        Socket tcp = plaintextClient(port);
        SSLSocket client = (SSLSocket) TlsTrust.trusting(authority.certificate()).getSocketFactory()
                .createSocket(tcp, hostname, port, true);
        verifyEndpointName(client);
        client.startHandshake();
        return client;
    }

    private static Socket plaintextClient(int port) throws IOException {
        Socket client = new Socket("127.0.0.1", port);
        client.setSoTimeout(5000);
        return client;
    }

    private static void verifyEndpointName(SSLSocket client) {
        SSLParameters parameters = client.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        client.setSSLParameters(parameters);
    }

    private static BufferedReader reader(Socket client) throws IOException {
        return new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
    }

    private static String send(Socket client, BufferedReader reader, byte[] command) throws IOException {
        client.getOutputStream().write(command);
        client.getOutputStream().flush();
        return reader.readLine();
    }

    /** Returns once the proxy has closed the connection, so it has finished with the client. */
    private static void awaitProxyClose(Socket tcp) throws IOException {
        InputStream in = tcp.getInputStream();
        try {
            while (in.read() != -1) {
                // drains whatever the proxy sent before closing, a TLS alert for one
            }
        } catch (SocketException expected) {
            // a reset closes the connection as well
        }
    }

    /** A backend that only accepts without blocking, so a check for a connection answers at once. */
    private static ServerSocketChannel idleBackend() throws IOException {
        ServerSocketChannel backend = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0));
        backend.configureBlocking(false);
        return backend;
    }

    private static int backendPort(ServerSocketChannel backend) throws IOException {
        return ((InetSocketAddress) backend.getLocalAddress()).getPort();
    }

    /** Answers every command on every relayed connection with PONG, until the backend is closed. */
    private static void answerEveryCommandWithPong(ServerSocket backend) {
        Thread.ofVirtual().start(() -> {
            while (!backend.isClosed()) {
                try {
                    Socket relayed = backend.accept();
                    Thread.ofVirtual().start(() -> answerWithPong(relayed));
                } catch (IOException expected) {
                    // the test closed the backend
                }
            }
        });
    }

    private static void answerWithPong(Socket relayed) {
        try (relayed) {
            RespReader reader = new RespReader(relayed.getInputStream());
            while (true) {
                reader.readCommand();
                relayed.getOutputStream().write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                relayed.getOutputStream().flush();
            }
        } catch (IOException expected) {
            // the proxy closed the relay, which ends the stream
        }
    }
}
