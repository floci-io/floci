package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import io.github.hectorvent.floci.core.common.Pem;
import io.github.hectorvent.floci.services.acm.CertificateGenerator;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies kubeconfig's {@code insecure-skip-tls-verify} against a real self-signed HTTPS
 * server, not just that the flag parses: without it, a self-signed server cert must still fail
 * the handshake, or the flag would silently do nothing.
 */
class KubernetesApiClientKubeconfigTest {

    private HttpsServer server;

    @BeforeAll
    static void registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void insecureSkipTlsVerifyTrustsASelfSignedServerCert(@TempDir Path tempDir) throws Exception {
        startSelfSignedServer();
        Path kubeconfig = writeKubeconfig(tempDir, true);

        KubernetesApiClient client = new KubernetesApiClient();
        client.initializeFromKubeconfigForTest(kubeconfig);

        assertThat(client.getPod("default", "whatever")).isEmpty();
    }

    @Test
    void withoutInsecureSkipTlsVerifyASelfSignedServerCertFailsTheHandshake(@TempDir Path tempDir) throws Exception {
        startSelfSignedServer();
        Path kubeconfig = writeKubeconfig(tempDir, false);

        KubernetesApiClient client = new KubernetesApiClient();
        client.initializeFromKubeconfigForTest(kubeconfig);

        assertThatThrownBy(() -> client.getPod("default", "whatever"))
                .hasMessageContaining("Kubernetes API GET");
    }

    private void startSelfSignedServer() throws Exception {
        CertificateGenerator generator = new CertificateGenerator();
        CertificateGenerator.GeneratedCertificate generated = generator.generateSelfSignedCertificate(
                "127.0.0.1", List.of("127.0.0.1"), KeyAlgorithm.RSA_2048);
        X509Certificate cert = Pem.parseCertificate(generated.certificatePem());
        PrivateKey privateKey = Pem.parsePrivateKey(generated.privateKeyPem());

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setKeyEntry("server", privateKey, new char[0], new Certificate[]{cert});
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, new char[0]);
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagerFactory.getKeyManagers(), null, null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.start();
    }

    private Path writeKubeconfig(Path tempDir, boolean insecureSkipTlsVerify) throws Exception {
        int port = server.getAddress().getPort();
        String config = """
                apiVersion: v1
                kind: Config
                current-context: test
                clusters:
                  - name: c
                    cluster:
                      server: https://127.0.0.1:%d
                      insecure-skip-tls-verify: %s
                contexts:
                  - name: test
                    context:
                      cluster: c
                      user: u
                users:
                  - name: u
                    user:
                      token: test-token
                """.formatted(port, insecureSkipTlsVerify);
        Path path = tempDir.resolve("config");
        Files.writeString(path, config);
        return path;
    }
}
