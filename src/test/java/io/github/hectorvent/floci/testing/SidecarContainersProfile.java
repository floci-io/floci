package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Assumptions;

import java.util.Map;

/**
 * Shared by every integration test that runs a real sidecar container, started by its manager
 * exactly as in production: the AppSync GraphQL sidecar, the CodeArtifact Reposilite and Verdaccio
 * sidecars, and the Verified Permissions Cedar sidecar. Quarkus builds the application once per
 * distinct profile class, so these classes share one application rather than each paying for
 * their own.
 *
 * <p>Namespaces the containers as {@code floci-aws-sidecar-test-<sidecar>}. Each manager removes
 * any container of its name before starting one, and the test config uses the same empty namespace
 * as a developer's running Floci, whose live sidecars would otherwise be killed. Every sidecar type
 * keeps its own name inside the namespace, so they never collide with each other.
 *
 * <p>Also pins the HTTP port and {@code floci.base-url} together. The AppSync resolver callback URL
 * is built from the base URL's port, which in production is the port Floci listens on. Under
 * {@code @QuarkusTest} the application listens on the test port instead, so without this the
 * sidecar would call back to 4566 and every resolver-backed field would fail with a connection
 * error.
 */
public class SidecarContainersProfile implements QuarkusTestProfile {

    public static final String NAMESPACE = "sidecar-test";

    private static final String TEST_PORT = "8081";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.docker.resource-namespace", NAMESPACE,
                "quarkus.http.test-port", TEST_PORT,
                "floci.base-url", "http://localhost:" + TEST_PORT);
    }

    /**
     * Skips the calling class without Docker. Locally it is also skipped when the sidecar image named
     * by {@code imageProperty} is not present, so a developer without registry access is not stuck;
     * in CI a missing image is a failure, since a vanished tag must not pass silently.
     */
    public static void requireDockerAndImage(String imageProperty) {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for sidecar integration tests");
        String image = ConfigProvider.getConfig().getValue(imageProperty, String.class);
        Assumptions.assumeTrue(imageUsable(image), "Sidecar image " + image + " is not present locally");
    }

    private static boolean isDockerAvailable() {
        return run("docker", "version", "--format", "{{.Server.Version}}");
    }

    private static boolean imageUsable(String image) {
        if ("true".equals(System.getenv("CI"))) {
            return true;
        }
        return run("docker", "image", "inspect", image);
    }

    private static boolean run(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            return process.waitFor() == 0;
        } catch (Exception ignored) {
            // Docker (or the image) isn't available; the caller turns this into a skipped test,
            // not a failure, so there is nothing more useful to log here.
            return false;
        }
    }
}
