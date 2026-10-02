package io.github.hectorvent.floci.testing;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Assumptions;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared by every integration test that runs a real sidecar container, started by its manager
 * exactly as in production: the AppSync GraphQL sidecar, the CodeArtifact Reposilite and Verdaccio
 * sidecars, the Verified Permissions Cedar sidecar, and the floci-duck sidecar behind the CUR
 * Parquet emitter. Quarkus builds the application once per distinct profile class, so these
 * classes share one application rather than each paying for their own.
 *
 * <p>Namespaces the containers as {@code floci-aws-sidecar-test-<hostkey>-<pid>-<sidecar>}. Each
 * manager removes any container of its name before starting one, and the test config uses the same
 * empty namespace as a developer's running Floci, whose live sidecars would otherwise be killed.
 * Every sidecar type keeps its own name inside the namespace, so they never collide with each
 * other. The test JVM's process id keeps two runs on one machine apart, since two live processes
 * never share an id; the host key, a short hash of the host name and the process id namespace,
 * does the same for runs on other machines or in containers that share the Docker daemon, whose
 * process ids mean nothing here. A normal shutdown stops the sidecars; those of a run that was
 * killed are removed by the next run on the same host key, in {@link #removeAbandonedSidecars()}.
 *
 * <p>Also sets the HTTP port and {@code floci.base-url} together. The AppSync resolver callback URL
 * is built from the base URL's port, which in production is the port Floci listens on. Under
 * {@code @QuarkusTest} the application listens on the test port instead, so without this the
 * sidecar would call back to 4566 and every resolver-backed field would fail with a connection
 * error, and floci-duck would reach for Floci's S3 on the wrong port. The port is a free one
 * chosen once when the class loads, rather than a fixed number, so two runs on the same machine do
 * not collide on it. It is released before Quarkus binds it, so another process can still take it
 * in between; the application then fails to start rather than sharing anything with that process.
 *
 * <p>Names both billing emitters as off, so a CUR or BCM report is only written when a test drives
 * the emitter itself. {@code CurEmissionScheduler} acts only on {@code daily} and
 * {@code synchronous}, so every other value leaves the emitters idle; the configured test default
 * already resolves to one of those inert values, and spelling it out here keeps the assumption
 * visible for the CUR classes that rely on it.
 */
public class SidecarContainersProfile implements QuarkusTestProfile {

    private static final String NAMESPACE_PREFIX = "sidecar-test-";

    private static final String HOST_KEY = hostKey();

    private static final String NAMESPACE = NAMESPACE_PREFIX + HOST_KEY + "-" + ProcessHandle.current().pid();

    private static final int TEST_PORT = freePort();

    private static boolean abandonedSidecarsRemoved;

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "floci.docker.resource-namespace", NAMESPACE,
                "quarkus.http.test-port", String.valueOf(TEST_PORT),
                "floci.base-url", "http://localhost:" + TEST_PORT,
                "floci.services.cur.emit-mode", "off",
                "floci.services.bcm-data-exports.emit-mode", "off");
    }

    /**
     * Identifies this machine and process id namespace, the scope a process id is valid in. On
     * Linux the namespace is {@code /proc/self/ns/pid}; elsewhere the host name has to do alone.
     */
    private static String hostKey() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException ignored) {
            // An unresolvable host name still leaves the process id namespace to tell hosts apart.
            host = "unknown-host";
        }
        String pidNamespace;
        try {
            pidNamespace = Files.readSymbolicLink(Path.of("/proc/self/ns/pid")).toString();
        } catch (IOException | UnsupportedOperationException ignored) {
            // Not Linux: there is no process id namespace to read, so the host name is the key.
            pidNamespace = "";
        }
        return Integer.toHexString((host + "|" + pidNamespace).hashCode());
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException("No free port for the sidecar test application", e);
        }
    }

    /**
     * Skips the calling class without Docker. Locally it is also skipped when the sidecar image named
     * by {@code imageProperty} is not present, so a developer without registry access is not stuck;
     * in CI a missing image is a failure, since a vanished tag must not pass silently.
     */
    public static void requireDockerAndImage(String imageProperty) {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for sidecar integration tests");
        removeAbandonedSidecars();
        String image = ConfigProvider.getConfig().getValue(imageProperty, String.class);
        Assumptions.assumeTrue(imageUsable(image), "Sidecar image " + image + " is not present locally");
    }

    /**
     * Removes the sidecars of earlier runs that were killed before they could stop them: containers
     * in a {@code sidecar-test-<hostkey>-<pid>} namespace carrying this host key, whose process no
     * longer exists. They would otherwise keep running and hold their published host ports, and no
     * later run would ever share their names to replace them. Sidecars of this run and of any other
     * live run are left alone, and so is every namespace with another host key, whose process id
     * cannot be checked from here. Runs once per JVM, from the first class that needs Docker.
     */
    private static synchronized void removeAbandonedSidecars() {
        if (abandonedSidecarsRemoved) {
            return;
        }
        abandonedSidecarsRemoved = true;
        for (String line : output("docker", "ps", "-a", "--filter", "label=floci_namespace",
                "--format", "{{.ID}} {{.Label \"floci_namespace\"}}")) {
            String[] idAndNamespace = line.split(" ", 2);
            if (idAndNamespace.length == 2 && isAbandoned(idAndNamespace[1])) {
                run("docker", "rm", "-f", idAndNamespace[0]);
            }
        }
    }

    private static boolean isAbandoned(String namespace) {
        String sameHost = NAMESPACE_PREFIX + HOST_KEY + "-";
        if (!namespace.startsWith(sameHost)) {
            return false;
        }
        try {
            long pid = Long.parseLong(namespace.substring(sameHost.length()));
            return ProcessHandle.of(pid).isEmpty();
        } catch (NumberFormatException notOurs) {
            // Some other namespace that happens to share the prefix: not one this profile made.
            return false;
        }
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

    private static List<String> output(String... command) {
        try {
            Process process = new ProcessBuilder(command).start();
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return process.waitFor() == 0 ? stdout.lines().toList() : List.of();
        } catch (IOException ignored) {
            // The Docker check just passed, so a failure here only means there is nothing to
            // clean up this time; the next run tries again.
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        }
    }
}
