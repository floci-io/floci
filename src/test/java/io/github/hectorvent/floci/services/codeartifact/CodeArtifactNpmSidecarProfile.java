package io.github.hectorvent.floci.services.codeartifact;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Assumptions;

import java.util.Map;

/**
 * Shared by every CodeArtifact integration test that needs a real Verdaccio sidecar container,
 * started by {@link VerdaccioSidecarManager} exactly as in production. Namespaces containers as
 * {@code floci-codeartifact-npm-test-*} so the manager's remove-before-start never touches a
 * developer's real running Floci. Needs Docker; skipped without it. Locally it is also skipped
 * when the pinned image is not present, so a developer without registry access is not stuck; in
 * CI a missing image is a failure, since a vanished tag must not pass silently.
 */
public class CodeArtifactNpmSidecarProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.docker.resource-namespace", "codeartifact-npm-test");
    }

    public static void requireDockerAndTheSidecarImage() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for CodeArtifact npm "
                + "sidecar integration tests");
        String image = ConfigProvider.getConfig().getValue("floci.services.codeartifact.npm-image", String.class);
        Assumptions.assumeTrue(imageUsable(image), "Verdaccio sidecar image " + image + " is not present locally");
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
