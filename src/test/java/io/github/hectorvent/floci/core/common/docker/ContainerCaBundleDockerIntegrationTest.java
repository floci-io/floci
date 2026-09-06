package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.command.WaitContainerResultCallback;
import io.github.hectorvent.floci.config.ContainerCaBundle;
import io.github.hectorvent.floci.config.FlociCertificateAuthority;
import io.github.hectorvent.floci.services.acm.model.KeyAlgorithm;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A container Floci launches, with no setup of its own, calls Floci over HTTPS and a public
 * HTTPS site. Python is the runtime because it honours {@code SSL_CERT_FILE}, the variable that
 * replaces the trust store: the image's own root directory is pointed away, so if the bundle
 * lacked the public roots the second call would fail, and if it lacked the Floci CA the first
 * would. Skipped without a Docker daemon, like the other {@code *DockerIntegrationTest} classes.
 */
@QuarkusTest
@TestProfile(ContainerCaBundleDockerIntegrationTest.Profile.class)
class ContainerCaBundleDockerIntegrationTest {

    private static final String IMAGE = "public.ecr.aws/docker/library/python:3.12-alpine";

    @Inject
    DockerClient dockerClient;

    @Inject
    ContainerBuilder containerBuilder;

    @Inject
    ContainerLifecycleManager lifecycle;

    @Inject
    ContainerDetector containerDetector;

    @ConfigProperty(name = "quarkus.http.test-ssl-port", defaultValue = "0")
    int testSslPort;

    @BeforeEach
    void requireDockerOnTheHost() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available");
        Assumptions.assumeFalse(containerDetector.isRunningInContainer(),
                "the container dials host.docker.internal, which only names a host-side Floci");
    }

    @Test
    void launchedContainerTrustsFlociAndPublicRootsWithoutAnySetup() throws Exception {
        String script = """
                import os, urllib.request, urllib.error
                bundle = "/etc/floci-ca-bundle.pem"
                for var in ("SSL_CERT_FILE", "CURL_CA_BUNDLE", "REQUESTS_CA_BUNDLE", "NODE_EXTRA_CA_CERTS", "AWS_CA_BUNDLE"):
                    assert os.environ.get(var) == bundle, (var, os.environ.get(var))
                assert open(bundle).read().count("BEGIN CERTIFICATE") > 1, "bundle must hold public roots and the Floci CA"
                ca = urllib.request.urlopen("https://host.docker.internal:%d/_floci/ca.pem", timeout=20).read()
                assert b"BEGIN CERTIFICATE" in ca, ca[:80]
                try:
                    urllib.request.urlopen("https://public.ecr.aws/", timeout=20).read()
                except urllib.error.HTTPError:
                    pass
                print("ok")
                """.formatted(testSslPort);

        ContainerSpec spec = containerBuilder.newContainer(IMAGE)
                .withName("floci-ca-bundle-test-" + Long.toString(System.nanoTime(), 36))
                .withHostDockerInternalOnLinux()
                // Only the bundle may supply roots: the image's /etc/ssl/certs must not rescue the public call.
                .withEnv("SSL_CERT_DIR", "/nonexistent")
                .withEntrypoint(List.of("python3", "-c", script))
                .build();

        ContainerLifecycleManager.ContainerInfo info = lifecycle.createAndStart(spec);
        try {
            Integer status = dockerClient.waitContainerCmd(info.containerId())
                    .exec(new WaitContainerResultCallback())
                    .awaitStatusCode(120, TimeUnit.SECONDS);
            assertEquals(0, status, () -> logs(info.containerId()));
        } finally {
            dockerClient.removeContainerCmd(info.containerId()).withForce(true).exec();
        }
    }

    private String logs(String containerId) {
        StringBuilder out = new StringBuilder();
        try {
            dockerClient.logContainerCmd(containerId).withStdOut(true).withStdErr(true)
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            out.append(new String(frame.getPayload(), StandardCharsets.UTF_8));
                        }
                    }).awaitCompletion(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            out.append("(could not read logs: ").append(e.getMessage()).append(')');
        }
        return out.toString();
    }

    private boolean isDockerAvailable() {
        try {
            dockerClient.pingCmd().exec();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Lays down what a TLS-on boot writes: the local CA, a leaf whose SANs include the name the
     * container dials, and the bundle. The bootstrap config source reads system properties and the
     * environment, which a test profile does not set, so the files are prepared here and the
     * registry keys point at them; {@code floci.tls.enabled} is what makes the lifecycle manager
     * inject the bundle. Quarkus binds every interface so the container reaches the test port.
     */
    public static final class Profile implements QuarkusTestProfile {

        private static final Path DATA_DIR = Path.of("target", "floci-ca-bundle-docker-test").toAbsolutePath();
        private static final Path TLS_DIR = DATA_DIR.resolve("tls");
        private static final Path CERT_FILE = TLS_DIR.resolve("floci-server.crt");
        private static final Path KEY_FILE = TLS_DIR.resolve("floci-server.key");

        static {
            try {
                FlociCertificateAuthority ca = FlociCertificateAuthority.loadOrCreate(TLS_DIR);
                var leaf = ca.issueServerCertificate("localhost",
                        List.of("localhost", "127.0.0.1", "host.docker.internal"), KeyAlgorithm.RSA_2048, null);
                Files.writeString(CERT_FILE, leaf.certificatePem());
                Files.writeString(KEY_FILE, leaf.privateKeyPem());
                ContainerCaBundle.write(TLS_DIR, ca.certificatePath());
            } catch (IOException | GeneralSecurityException e) {
                throw new IllegalStateException("Failed to prepare the TLS test files", e);
            }
        }

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.tls.enabled", "true",
                    "floci.tls.aws-https-port", "0",
                    "floci.storage.persistent-path", DATA_DIR.toString(),
                    "quarkus.tls.key-store.pem.0.cert", CERT_FILE.toString(),
                    "quarkus.tls.key-store.pem.0.key", KEY_FILE.toString(),
                    "quarkus.http.insecure-requests", "enabled",
                    "quarkus.http.host", "0.0.0.0");
        }
    }
}
