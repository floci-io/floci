package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ContainerStorageHelperTest {

    @Test
    void resourceNamesStayUnchangedWithoutNamespace() {
        assertEquals("floci-rds-db1", ContainerStorageHelper.resourceName("rds", null, "db1"));
        assertEquals("floci-rds-vol1", ContainerStorageHelper.resourceName(config(""), "rds", "vol1", "db1"));
        assertEquals("floci-opensearch-domain1", ContainerStorageHelper.resourceName(config(""), "opensearch", null, "domain1"));
        assertEquals("floci-ec2-i-123", ContainerStorageHelper.dockerName(config(""), "floci-ec2-i-123"));
    }

    @Test
    void resourceNamesIncludeSanitizedNamespaceWhenConfigured() {
        EmulatorConfig config = config(" run/one ");

        assertEquals("floci-run-one-rds-db1", ContainerStorageHelper.resourceName(config, "rds", null, "db1"));
        assertEquals("floci-run-one-rds-vol1", ContainerStorageHelper.resourceName(config, "rds", "vol1", "db1"));
        assertEquals("floci-run-one-ec2-i-123", ContainerStorageHelper.dockerName(config, "floci-ec2-i-123"));
        assertEquals("floci-run-one-ui", ContainerStorageHelper.dockerName(config, "floci-ui"));
    }

    @Test
    void hostResourcePathsIncludeNamespaceWhenConfigured() {
        EmulatorConfig config = config("run-one");

        assertEquals(Path.of("/tmp/floci/run-one/rds/db1"), ContainerStorageHelper.hostResourcePath(config, "rds", "db1"));
    }

    @Test
    void unsafeNamespaceSegmentsAreIgnored() {
        EmulatorConfig config = config("..");

        assertEquals(Path.of("/tmp/floci/rds/db1"), ContainerStorageHelper.hostResourcePath(config, "rds", "db1"));
        assertEquals("floci-rds-db1", ContainerStorageHelper.resourceName(config, "rds", null, "db1"));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws"),
                ContainerStorageHelper.defaultLabels(config));
    }

    @Test
    void defaultLabelsIdentifyThisEmulator() {
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws"),
                ContainerStorageHelper.defaultLabels(config("")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws", "floci_namespace", "run-one"),
                ContainerStorageHelper.defaultLabels(config(" run/one ")));
        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws"),
                ContainerStorageHelper.defaultLabels(null));
    }

    @Test
    void prefixedDockerNameSwapsTheBasePrefix() {
        assertEquals("acme-my-fn-abc123",
                ContainerStorageHelper.prefixedDockerName(config(""), "acme", "my-fn-abc123"));
        assertEquals("acme-run-one-my-fn-abc123",
                ContainerStorageHelper.prefixedDockerName(config(" run/one "), "acme", "my-fn-abc123"));
        // The default prefix through this path matches what dockerName produces.
        assertEquals(ContainerStorageHelper.dockerName(config("run-one"), "floci-my-fn-abc123"),
                ContainerStorageHelper.prefixedDockerName(config("run-one"), "floci", "my-fn-abc123"));
    }

    @Test
    void extraLabelsAreMergedIntoDefaultLabels() {
        // A dotted key — exactly the shape that motivates list-of-entries config over a Map,
        // whose env-var naming convention cannot express such keys.
        EmulatorConfig config = config("", java.util.List.of(
                label("com.example.project", "my-project"),
                label("environment", "dev")));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws",
                        "com.example.project", "my-project", "environment", "dev"),
                ContainerStorageHelper.defaultLabels(config));
    }

    @Test
    void extraLabelsCannotOverrideReservedKeys() {
        // The reserved labels drive container/volume discovery and pruning; a user label must
        // never be able to break `docker volume prune --filter label=floci=true` cleanup.
        EmulatorConfig config = config(" run/one ", java.util.List.of(
                label("floci", "false"),
                label("floci_emulator", "spoofed"),
                label("floci_namespace", "spoofed"),
                label("kept", "yes")));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws",
                        "floci_namespace", "run-one", "kept", "yes"),
                ContainerStorageHelper.defaultLabels(config));
    }

    @Test
    void blankExtraLabelKeysAreIgnored() {
        EmulatorConfig config = config("", java.util.List.of(
                label("  ", "dropped"),
                label(null, "dropped"),
                label("kept", null)));

        assertEquals(
                Map.of("floci", "true", "floci_emulator", "floci-aws", "kept", ""),
                ContainerStorageHelper.defaultLabels(config));
    }

    private static EmulatorConfig.DockerConfig.LabelEntry label(String key, String value) {
        return new EmulatorConfig.DockerConfig.LabelEntry() {
            @Override
            public String key() {
                return key;
            }

            @Override
            public String value() {
                return value;
            }
        };
    }

    private static EmulatorConfig config(String namespace) {
        return config(namespace, java.util.List.of());
    }

    private static EmulatorConfig config(
            String namespace, java.util.List<EmulatorConfig.DockerConfig.LabelEntry> extraLabels) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.docker()).thenReturn(docker);
        when(config.storage()).thenReturn(storage);
        when(docker.resourceNamespace()).thenReturn(namespace.isBlank() ? Optional.empty() : Optional.of(namespace));
        when(docker.extraLabels()).thenReturn(extraLabels);
        when(storage.hostPersistentPath()).thenReturn("/tmp/floci");
        return config;
    }
}
