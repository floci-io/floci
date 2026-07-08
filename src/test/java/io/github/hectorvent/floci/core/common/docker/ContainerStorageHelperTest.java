package io.github.hectorvent.floci.core.common.docker;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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
    }

    private static EmulatorConfig config(String namespace) {
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig docker = mock(EmulatorConfig.DockerConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(config.docker()).thenReturn(docker);
        when(config.storage()).thenReturn(storage);
        when(docker.resourceNamespace()).thenReturn(namespace.isBlank() ? Optional.empty() : Optional.of(namespace));
        when(storage.hostPersistentPath()).thenReturn("/tmp/floci");
        return config;
    }
}
