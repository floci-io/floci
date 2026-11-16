package io.floci.aws.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.smallrye.config.WithDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplicationDefaultsTest {

    private static JsonNode productionConfig() throws IOException {
        return new YAMLMapper().readTree(Path.of("src/main/resources/application.yml").toFile());
    }

    @Test
    void productionConfigEnablesCloudTrailByDefault() throws IOException {
        try (InputStream configStream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            assertNotNull(configStream, "application.yml should be available on the test classpath");
            JsonNode config = new YAMLMapper().readTree(configStream);

            assertTrue(config.path("floci")
                            .path("services")
                            .path("cloudtrail")
                            .path("enabled")
                            .asBoolean(false),
                    "application.yml should enable CloudTrail by default");
        }
    }

    @Test
    void productionConfigUsesExpectedRequestSizeLimit() throws IOException {
        JsonNode config = productionConfig();

        assertEquals(2048,
                config.path("floci").path("protocols").path("max-request-size").asInt(),
                "production application.yml should allow 2048 MB request bodies by default");
    }

    @Test
    void productionConfigUsesTheAwsSqsMaximumMessageSize() throws IOException, NoSuchMethodException {
        JsonNode config = productionConfig();

        assertEquals(1048576,
                config.path("floci").path("services").path("sqs").path("max-message-size").asInt(),
                "production application.yml should use the AWS SQS maximum of 1048576 bytes");

        WithDefault fallback = EmulatorConfig.SqsServiceConfig.class
                .getMethod("maxMessageSize")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "maxMessageSize should declare a fallback default");
        assertEquals("1048576", fallback.value(),
                "the EmulatorConfig fallback should match the AWS SQS maximum too");
    }

    @Test
    void productionConfigListensOnLoopbackWithoutNetworkExposureConsent() throws IOException, NoSuchMethodException {
        JsonNode config = productionConfig();

        assertEquals("127.0.0.1", config.path("quarkus").path("http").path("host").asText(),
                "production application.yml should listen on loopback by default");
        assertFalse(config.path("floci").path("security").path("allow-unsafe-network-exposure").asBoolean(true),
                "production application.yml should not allow a non-loopback listener by default");

        WithDefault fallback = EmulatorConfig.SecurityConfig.class
                .getMethod("allowUnsafeNetworkExposure")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "allowUnsafeNetworkExposure should declare a fallback default");
        assertEquals("false", fallback.value(), "the EmulatorConfig fallback should refuse exposure too");
    }

    @Test
    void productionConfigDoesNotSeedIamDeployerPrincipalByDefault() throws IOException {
        JsonNode config = productionConfig();

        assertFalse(config.path("floci")
                        .path("services")
                        .path("iam")
                        .path("seed-deployer-principal")
                        .asBoolean(true),
                "production application.yml should not create default admin credentials unless enabled");
    }

    @Test
    void productionConfigRejectsPrivateJwtTargetsByDefault() throws Exception {
        JsonNode config = productionConfig();

        assertFalse(config.path("floci")
                        .path("security")
                        .path("allow-private-jwt-targets")
                        .asBoolean(true),
                "production application.yml should reject private JWT issuer and JWKS targets");

        WithDefault fallback = EmulatorConfig.SecurityConfig.class
                .getMethod("allowPrivateJwtTargets")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "allowPrivateJwtTargets should declare a fallback default");
        assertEquals("false", fallback.value());
    }

    @Test
    void productionConfigBindsRdsIamTokensToTheEndpointByDefault() throws NoSuchMethodException {
        WithDefault fallback = EmulatorConfig.RdsServiceConfig.class
                .getMethod("iamTokenEndpointBinding")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "iamTokenEndpointBinding should declare a fallback default");
        assertEquals("true", fallback.value(),
                "a stock Floci should refuse a PostgreSQL IAM token generated for another endpoint, as RDS does");
    }

    // Catches: a max-connections in application.yml that silently disagrees with the @WithDefault fallback.
    @Test
    void productionConfigDeclaresTheDockerPoolAtItsDefault() throws IOException, NoSuchMethodException {
        int maxConnections = productionConfig()
                .path("floci").path("docker").path("max-connections").asInt();

        WithDefault fallback = EmulatorConfig.DockerConfig.class
                .getMethod("maxConnections")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "maxConnections should declare a fallback default");
        assertEquals(String.valueOf(maxConnections), fallback.value(),
                "the @WithDefault must match application.yml, which wins over it");
    }

    // Catches: a streaming pool too small for the Lambda containers the runtime API port range allows,
    // so log streams, exit watchers and extensions fail to get a connection well before that limit.
    @Test
    void productionConfigSizesTheStreamingDockerPoolForTheLambdaRuntimeApiPortRange() throws IOException {
        JsonNode floci = productionConfig()
                .path("floci");
        int streamingMaxConnections = floci.path("docker").path("streaming-max-connections").asInt();
        JsonNode lambda = floci.path("services").path("lambda");
        int runtimeApiPorts = lambda.path("runtime-api-max-port").asInt()
                - lambda.path("runtime-api-base-port").asInt() + 1;

        assertTrue(streamingMaxConnections >= 4 * runtimeApiPorts,
                "each live Lambda container holds a streaming connection for its log follow, its exit watcher "
                        + "and each extension, so streaming-max-connections " + streamingMaxConnections
                        + " should cover the " + runtimeApiPorts
                        + " containers the runtime API port range allows with two extensions each");
    }

    // Catches: streaming-max-connections declared and documented but absent from the shipped
    // application.yml, or a yml value that silently disagrees with the @WithDefault fallback.
    @Test
    void productionConfigDeclaresTheStreamingDockerPoolAtItsDefault() throws IOException, NoSuchMethodException {
        JsonNode streamingMaxConnections = productionConfig()
                .path("floci").path("docker").path("streaming-max-connections");

        assertFalse(streamingMaxConnections.isMissingNode(),
                "streaming-max-connections should be declared in application.yml");
        assertEquals(2048, streamingMaxConnections.asInt());

        WithDefault fallback = EmulatorConfig.DockerConfig.class
                .getMethod("streamingMaxConnections")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "streamingMaxConnections should declare a fallback default");
        assertEquals("2048", fallback.value(),
                "the @WithDefault must match application.yml, which wins over it");
    }

    // Catches: connection-request-timeout-seconds missing from the shipped application.yml, or a yml
    // value that silently disagrees with the @WithDefault fallback.
    @Test
    void productionConfigDeclaresTheDockerLeaseTimeoutAtItsDefault() throws IOException, NoSuchMethodException {
        JsonNode leaseTimeout = productionConfig()
                .path("floci").path("docker").path("connection-request-timeout-seconds");

        assertFalse(leaseTimeout.isMissingNode(),
                "connection-request-timeout-seconds should be declared in application.yml");
        assertEquals(30, leaseTimeout.asInt());

        WithDefault fallback = EmulatorConfig.DockerConfig.class
                .getMethod("connectionRequestTimeoutSeconds")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "connectionRequestTimeoutSeconds should declare a fallback default");
        assertEquals("30", fallback.value(),
                "the @WithDefault must match application.yml, which wins over it");
    }

    // Catches: api-timeout-seconds missing from the shipped application.yml, or a yml value that
    // silently disagrees with the @WithDefault fallback.
    @Test
    void productionConfigDeclaresTheDockerApiTimeoutAtItsDefault() throws IOException, NoSuchMethodException {
        JsonNode apiTimeout = productionConfig()
                .path("floci").path("docker").path("api-timeout-seconds");

        assertFalse(apiTimeout.isMissingNode(), "api-timeout-seconds should be declared in application.yml");
        assertEquals(300, apiTimeout.asInt());

        WithDefault fallback = EmulatorConfig.DockerConfig.class
                .getMethod("apiTimeoutSeconds")
                .getAnnotation(WithDefault.class);
        assertNotNull(fallback, "apiTimeoutSeconds should declare a fallback default");
        assertEquals("300", fallback.value(),
                "the @WithDefault must match application.yml, which wins over it");
    }
}
