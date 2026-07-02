package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LaunchedContainerAwsEnv#sdkBaselineEnv}: the AWS SDK baseline
 * (region, credentials and the Floci endpoint) injected into every container Floci launches.
 * The Floci endpoint is stubbed via {@link ContainerReachableEndpoint} so the test does not
 * depend on host networking.
 */
class LaunchedContainerAwsEnvTest {

    private LaunchedContainerAwsEnv awsEnvWithEndpoint(String baseUrl) {
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.baseUrl()).thenReturn(baseUrl);
        return new LaunchedContainerAwsEnv(mock(EmulatorConfig.class), endpoint);
    }

    @Test
    void injectsRegionEndpointAndPlaceholderCredentialsWhenNoConfigDir() {
        LaunchedContainerAwsEnv awsEnv = awsEnvWithEndpoint("http://localhost:4566");

        List<String> env = awsEnv.sdkBaselineEnv("us-east-1", Optional.empty());

        assertTrue(env.contains("AWS_DEFAULT_REGION=us-east-1"));
        assertTrue(env.contains("AWS_REGION=us-east-1"));

        // Floci endpoint the SDK should target, reachable from inside the container.
        assertTrue(env.contains("FLOCI_HOSTNAME=localhost"));
        assertTrue(env.contains("FLOCI_ENDPOINT=http://localhost:4566"));
        assertTrue(env.contains("AWS_ENDPOINT_URL=http://localhost:4566"));

        // Placeholder credentials: the host env var when set, otherwise "test".
        String expectedAk = System.getenv("AWS_ACCESS_KEY_ID") != null ? System.getenv("AWS_ACCESS_KEY_ID") : "test";
        String expectedSk = System.getenv("AWS_SECRET_ACCESS_KEY") != null ? System.getenv("AWS_SECRET_ACCESS_KEY") : "test";
        String expectedSt = System.getenv("AWS_SESSION_TOKEN") != null ? System.getenv("AWS_SESSION_TOKEN") : "test";
        assertTrue(env.contains("AWS_ACCESS_KEY_ID=" + expectedAk));
        assertTrue(env.contains("AWS_SECRET_ACCESS_KEY=" + expectedSk));
        assertTrue(env.contains("AWS_SESSION_TOKEN=" + expectedSt));

        // No mounted-config file paths when credentials are injected directly.
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_SHARED_CREDENTIALS_FILE=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_CONFIG_FILE=")));
    }

    @Test
    void pointsSdkAtMountedConfigDirAndSkipsPlaceholderCredentials() {
        LaunchedContainerAwsEnv awsEnv = awsEnvWithEndpoint("http://localhost:4566");

        List<String> env = awsEnv.sdkBaselineEnv("eu-west-1", Optional.of("/opt/aws-config"));

        assertTrue(env.contains("AWS_DEFAULT_REGION=eu-west-1"));
        assertTrue(env.contains("AWS_REGION=eu-west-1"));

        // A mounted ~/.aws directory: point the SDK at explicit file paths, discover credentials there.
        assertTrue(env.contains("AWS_SHARED_CREDENTIALS_FILE=/opt/aws-config/credentials"));
        assertTrue(env.contains("AWS_CONFIG_FILE=/opt/aws-config/config"));

        // Credentials must not be injected when the SDK discovers them from the mounted directory.
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_ACCESS_KEY_ID=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_SECRET_ACCESS_KEY=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_SESSION_TOKEN=")));

        assertTrue(env.contains("AWS_ENDPOINT_URL=http://localhost:4566"));
    }

    @Test
    void blankConfigDirFallsBackToPlaceholderCredentials() {
        LaunchedContainerAwsEnv awsEnv = awsEnvWithEndpoint("http://localhost:4566");

        List<String> env = awsEnv.sdkBaselineEnv("us-east-1", Optional.of("   "));

        // A blank directory is treated as "not mounted": inject placeholder credentials instead.
        assertTrue(env.stream().anyMatch(e -> e.startsWith("AWS_ACCESS_KEY_ID=")));
        assertTrue(env.stream().noneMatch(e -> e.startsWith("AWS_SHARED_CREDENTIALS_FILE=")));
    }

    @Test
    void derivesFlociHostnameFromEndpointHost() {
        LaunchedContainerAwsEnv awsEnv = awsEnvWithEndpoint("https://host.docker.internal:4566");

        List<String> env = awsEnv.sdkBaselineEnv("us-east-1", Optional.empty());

        assertEquals(1, env.stream().filter(e -> e.equals("FLOCI_HOSTNAME=host.docker.internal")).count());
        assertTrue(env.contains("AWS_ENDPOINT_URL=https://host.docker.internal:4566"));
    }
}
