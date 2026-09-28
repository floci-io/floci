package io.github.hectorvent.floci.services.codeartifact;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Assumptions;

import java.util.Map;

/**
 * Shared by every CodeArtifact integration test that needs the real Reposilite sidecar container,
 * started by {@link ReposiliteSidecarManager} exactly as in production. Namespaces the container
 * as {@code floci-codeartifact-maven-test-reposilite} so the manager's remove-before-start never
 * touches a developer's real running Floci. Needs Docker; skipped without it. Locally it is also
 * skipped when the pinned image is not present, so a developer without registry access is not
 * stuck; in CI a missing image is a failure, since a vanished tag must not pass silently.
 */
public class CodeArtifactMavenSidecarProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.docker.resource-namespace", "codeartifact-maven-test");
    }

    public static void requireDockerAndTheSidecarImage() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for CodeArtifact Maven "
                + "sidecar integration tests");
        String image = ConfigProvider.getConfig().getValue("floci.services.codeartifact.maven-image", String.class);
        Assumptions.assumeTrue(imageUsable(image), "Reposilite sidecar image " + image + " is not present locally");
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
        } catch (Exception e) {
            return false;
        }
    }
}
