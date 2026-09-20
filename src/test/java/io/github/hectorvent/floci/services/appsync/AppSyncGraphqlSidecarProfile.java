package io.github.hectorvent.floci.services.appsync;

import io.quarkus.test.junit.QuarkusTestProfile;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Assumptions;

import java.util.Map;

/**
 * Shared by every AppSync integration test that needs the real GraphQL sidecar container, started
 * by {@link GraphqlSidecarManager} exactly as in production. Namespaces the container as
 * {@code floci-appsync-test-graphql} so the manager's remove-before-start never touches a
 * developer's real running Floci. Needs Docker; skipped without it. Locally it is also skipped
 * when the pinned image is not present, so a developer without registry access is not stuck; in CI
 * a missing image is a failure, since a vanished tag must not pass silently.
 *
 * <p>Every test class that uses this profile shares one Quarkus test application instance (and
 * one running sidecar container) rather than paying a restart for each, since Quarkus keys its
 * test-application cache by the profile class identity.
 */
public class AppSyncGraphqlSidecarProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("floci.docker.resource-namespace", "appsync-test");
    }

    public static void requireDockerAndTheSidecarImage() {
        Assumptions.assumeTrue(isDockerAvailable(), "Docker daemon must be available for AppSync integration tests");
        String image = ConfigProvider.getConfig().getValue("floci.services.appsync.graphql-image", String.class);
        Assumptions.assumeTrue(imageUsable(image), "GraphQL sidecar image " + image + " is not present locally");
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
