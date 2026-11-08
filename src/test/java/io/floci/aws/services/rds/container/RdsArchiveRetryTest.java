package io.floci.aws.services.rds.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.rds.model.DatabaseEngine;
import io.floci.aws.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RdsArchiveRetryTest {
    private static final String CONTAINER = "rds-retry-id";

    @TempDir
    Path temporary;

    private DockerClient docker;
    private ContainerLifecycleManager lifecycle;
    private RdsContainerManager manager;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.create(any())).thenReturn(CONTAINER);
        when(lifecycle.startCreated(eq(CONTAINER), any())).thenReturn(new ContainerInfo(CONTAINER,
                Map.of(3306, new EndpointInfo("localhost", 3306))));
        ContainerBuilder builder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder spec = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(spec.build()).thenReturn(new ContainerSpec("test-image"));
        when(builder.newContainer(anyString())).thenReturn(spec);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().rds().dockerNetwork()).thenReturn(Optional.empty());
        when(config.storage().hostPersistentPath()).thenReturn(temporary.toString());
        when(config.storage().mode()).thenReturn("persistent");
        ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        manager = new RdsContainerManager(builder, lifecycle, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, new RegionResolver("us-east-1", "000000000000"),
                mock(ServiceConfigAccess.class), 0);
    }

    @AfterEach
    void tearDown() {
        manager.clear();
        manager.shutdownAutoPause();
    }

    @ParameterizedTest
    @CsvSource({"MYSQL,0", "MYSQL,73", "MYSQL,8192", "MARIADB,0", "MARIADB,73", "MARIADB,8192"})
    void masterGrantRetriesConsumedUploadBeforeStarting(DatabaseEngine engine, int consumedBytes) throws Exception {
        DockerArchiveUploadFixture uploads = failingUploads(consumedBytes, 1);

        RdsContainerHandle handle = manager.start("db", "volume", engine, "test-image", "we'ird\\", "password", "db");

        assertEquals(CONTAINER, handle.getContainerId());
        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        verify(uploads.commands.get(1)).withRemotePath("/docker-entrypoint-initdb.d");
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("floci-master-grants.sql", entry.getName());
            assertEquals(0644, entry.getMode());
            assertArrayEquals("GRANT ALL PRIVILEGES ON *.* TO 'we\\'ird\\\\'@'%' WITH GRANT OPTION;"
                    .getBytes(StandardCharsets.UTF_8), tar.readAllBytes());
            assertNull(tar.getNextEntry());
        }
        InOrder order = inOrder(lifecycle, uploads.commands.get(1));
        order.verify(lifecycle).create(any());
        order.verify(uploads.commands.get(1)).exec();
        order.verify(lifecycle).startCreated(eq(CONTAINER), any());
        verify(docker, never()).execCreateCmd(anyString());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void postgresRestoreRetriesBothEntriesBeforeRunningSql(int consumedBytes) throws Exception {
        DockerArchiveUploadFixture uploads = failingUploads(consumedBytes, 1);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        String dump = "-- snapshot é\n".repeat(2048);

        manager.restorePostgresSnapshot(CONTAINER, "admin", dump);

        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        verify(uploads.commands.get(1)).withRemotePath("/tmp");
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()))) {
            assertTarEntry(tar, "dump.sql", dump);
            assertTarEntry(tar, "restore.sh", RdsContainerManager.postgresRestoreScript());
            assertNull(tar.getNextEntry());
        }
        assertEquals(List.of(List.of("sh", "/tmp/restore.sh", "admin")), commands);
        InOrder order = inOrder(uploads.commands.get(1), docker);
        order.verify(uploads.commands.get(1)).exec();
        order.verify(docker).execCreateCmd(CONTAINER);
    }

    @ParameterizedTest
    @CsvSource({"MYSQL,false", "MARIADB,false", "MYSQL,true", "MARIADB,true"})
    void failedGrantUploadCleansUpAndReleasesOwnership(DatabaseEngine engine, boolean permanent) {
        DockerArchiveUploadFixture uploads = failingUploads(73, Integer.MAX_VALUE);
        if (permanent) {
            uploads.failure = new NotFoundException("container missing");
        }

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.start("db", "volume", engine, "test-image", "admin", "password", "db"));

        assertSame(uploads.failure, failure.getCause());
        assertEquals(permanent ? 1 : 6, uploads.commands.size());
        verify(lifecycle, never()).startCreated(anyString(), any());
        verify(lifecycle).stopAndRemoveStrict(CONTAINER, null);
        verify(docker, never()).execCreateCmd(anyString());

        uploads.failures = 0;
        assertEquals(CONTAINER,
                manager.start("db", "volume", engine, "test-image", "admin", "password", "db").getContainerId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedRestoreUploadNeverRunsSql(boolean permanent) {
        DockerArchiveUploadFixture uploads = failingUploads(73, Integer.MAX_VALUE);
        if (permanent) {
            uploads.failure = new NotFoundException("container missing");
        }

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.restorePostgresSnapshot(CONTAINER, "admin", "SELECT 1;"));

        assertSame(uploads.failure, failure.getCause());
        assertEquals(permanent ? 1 : 6, uploads.commands.size());
        verify(docker, never()).execCreateCmd(anyString());
    }

    @Test
    void sqlFailureDoesNotRetryUploadOrSql() {
        DockerArchiveUploadFixture uploads = failingUploads(0, 0);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 1, "", "invalid dump");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.restorePostgresSnapshot(CONTAINER, "admin", "SELECT 1;"));

        assertTrue(failure.getMessage().contains("psql restore failed with exit code 1: invalid dump"));
        assertEquals(1, uploads.commands.size());
        assertEquals(List.of(List.of("sh", "/tmp/restore.sh", "admin")), commands);
    }

    @Test
    void sqlTransportFailureDoesNotRetryUploadOrSql() {
        DockerArchiveUploadFixture uploads = failingUploads(0, 0);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        RuntimeException transportFailure = new RuntimeException(new IOException("Connection reset"));
        when(docker.execStartCmd(anyString())).thenThrow(transportFailure);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> manager.restorePostgresSnapshot(CONTAINER, "admin", "SELECT 1;"));

        assertSame(transportFailure, failure);
        assertEquals(1, uploads.commands.size());
        assertEquals(List.of(List.of("sh", "/tmp/restore.sh", "admin")), commands);
    }

    private static void assertTarEntry(TarArchiveInputStream tar, String name, String content) throws IOException {
        TarArchiveEntry entry = tar.getNextEntry();
        assertEquals(name, entry.getName());
        assertEquals(0755, entry.getMode());
        assertArrayEquals(content.getBytes(StandardCharsets.UTF_8), tar.readAllBytes());
    }

    private DockerArchiveUploadFixture failingUploads(int consumedBytes, int failures) {
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, CONTAINER);
        uploads.failures = failures;
        uploads.consumedBytes = consumedBytes;
        uploads.failure = new RuntimeException(new IOException("Connection reset"));
        return uploads;
    }
}
