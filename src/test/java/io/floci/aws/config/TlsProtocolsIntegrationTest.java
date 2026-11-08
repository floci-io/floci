package io.floci.aws.config;

import io.floci.aws.testutil.FreePorts;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static io.floci.aws.config.TlsTestSupport.dnsSans;
import static io.floci.aws.config.TlsTestSupport.trustOnlyTheCa;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@QuarkusTest
@TestProfile(SelfSignedTlsProfile.class)
class TlsProtocolsIntegrationTest {

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int httpsPort;

    @Inject
    TlsCertificateManager manager;

    private Set<String> originalHostnames;
    private Vertx vertx;
    private TlsProxyServer proxy;
    private int publicPort;

    @BeforeEach
    void startProxy() throws Exception {
        originalHostnames = manager.knownHostnames();
        publicPort = FreePorts.loopbackIpv4Port();
        vertx = Vertx.vertx();
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.TlsConfig tls = mock(EmulatorConfig.TlsConfig.class);
        when(config.port()).thenReturn(publicPort);
        when(config.tls()).thenReturn(tls);
        when(tls.enabled()).thenReturn(true);
        when(tls.awsHttpsPort()).thenReturn(0);
        when(config.security()).thenReturn(mock(EmulatorConfig.SecurityConfig.class));
        proxy = new TlsProxyServer(vertx, config, "127.0.0.1", httpPort, httpsPort);
    }

    @AfterEach
    void restoreCertificateAndCloseProxy() throws Exception {
        try {
            manager.clear();
            originalHostnames.forEach(manager::ensureHost);
        } finally {
            if (proxy != null) {
                proxy.stop();
            }
            if (vertx != null) {
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest(name = "{0} works through the public proxy before and after certificate reload")
    @ValueSource(strings = {"TLSv1.2", "TLSv1.3"})
    void protocolWorksBeforeAndAfterCertificateReload(String protocol) throws Exception {
        String newHost = "tls-" + UUID.randomUUID() + ".reload.localhost.floci.io";
        X509Certificate before = health(publicPort, "localhost", protocol);
        assertFalse(dnsSans(before).contains(newHost), "the new name must require a certificate reload");

        manager.ensureHost(newHost);

        X509Certificate after = health(publicPort, newHost, protocol);
        assertTrue(dnsSans(after).contains(newHost), "the listener must serve the newly issued SAN");
        assertNotEquals(before, after, "the listener must have reloaded its certificate");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void obsoleteProtocolsAreRejectedByTheServerBeforeAndAfterReload(int minorVersion) throws Exception {
        assertProtocolVersionAlert(minorVersion);
        String newHost = "legacy-" + UUID.randomUUID() + ".reload.localhost.floci.io";
        X509Certificate before = health(publicPort, "localhost", "TLSv1.3");

        manager.ensureHost(newHost);

        X509Certificate after = health(publicPort, newHost, "TLSv1.3");
        assertTrue(dnsSans(after).contains(newHost));
        assertNotEquals(before, after);
        assertProtocolVersionAlert(minorVersion);
    }

    private void assertProtocolVersionAlert(int minorVersion) throws Exception {
        // A raw TLS 1.0/1.1 ClientHello reaches the server instead of being rejected locally
        // by the JDK client's disabled-algorithm policy. The only offered cipher is TLS_RSA_WITH_AES_128_CBC_SHA.
        byte[] hello = new byte[50];
        hello[0] = 22;
        hello[1] = 3;
        hello[2] = (byte) minorVersion;
        hello[4] = 45;
        hello[5] = 1;
        hello[8] = 41;
        hello[9] = 3;
        hello[10] = (byte) minorVersion;
        hello[45] = 2;
        hello[47] = 0x2f;
        hello[48] = 1;
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", publicPort), 5000);
            socket.setSoTimeout(5000);
            socket.getOutputStream().write(hello);
            byte[] alert = socket.getInputStream().readNBytes(7);
            assertEquals(7, alert.length, "a complete TLS alert must come from the server");
            assertEquals(21, alert[0], "TLS alert record");
            assertEquals(2, alert[5], "fatal alert");
            assertEquals(70, alert[6], "protocol_version alert");
        }
    }

    private static X509Certificate health(int port, String host, String protocol) throws Exception {
        try (Socket transport = new Socket()) {
            transport.connect(new InetSocketAddress("127.0.0.1", port), 5000);
            try (SSLSocket socket = (SSLSocket) trustOnlyTheCa().getSocketFactory()
                    .createSocket(transport, host, port, true)) {
                socket.setSoTimeout(5000);
                SSLParameters parameters = socket.getSSLParameters();
                parameters.setProtocols(new String[]{protocol});
                parameters.setServerNames(List.of(new SNIHostName(host)));
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                socket.setSSLParameters(parameters);
                socket.startHandshake();
                assertEquals(protocol, socket.getSession().getProtocol(), "the pinned protocol must be negotiated");

                // The health endpoint uses the management host, independently of TLS SNI.
                socket.getOutputStream().write(("GET /_floci/health HTTP/1.1\r\nHost: localhost"
                        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                String status = new BufferedReader(new InputStreamReader(
                        socket.getInputStream(), StandardCharsets.US_ASCII)).readLine();
                assertNotNull(status, "the real Quarkus backend must return an HTTP response");
                assertEquals("200", status.split(" ")[1], status);
                return (X509Certificate) socket.getSession().getPeerCertificates()[0];
            }
        }
    }
}
