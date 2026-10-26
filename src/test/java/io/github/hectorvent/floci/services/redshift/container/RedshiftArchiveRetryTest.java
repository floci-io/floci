package io.github.hectorvent.floci.services.redshift.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerExecStubs;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftArchiveRetryTest {
    private static final String ACCOUNT = "111111111111";
    private static final String CONTAINER = "redshift-id";

    @TempDir
    Path temporary;

    private DockerClient docker;
    private RedshiftContainerManager manager;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        ContainerBuilder builder = mock(ContainerBuilder.class);
        when(builder.newContainer(anyString())).thenReturn(mock(ContainerBuilder.Builder.class, RETURNS_SELF));
        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.getDockerClient()).thenReturn(docker);
        when(lifecycle.createAndStartEphemeral(any())).thenReturn(
                new ContainerInfo(CONTAINER, Map.of(5432, new EndpointInfo("localhost", 5432))));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().redshift().imageVersion()).thenReturn("postgres:16-alpine");
        when(config.services().redshift().dockerNetwork()).thenReturn(Optional.empty());
        when(config.defaultRegion()).thenReturn("us-east-1");
        ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        when(docker.copyArchiveToContainerCmd(CONTAINER)).thenReturn(mock(CopyArchiveToContainerCmd.class, RETURNS_SELF));
        manager = new RedshiftContainerManager(builder, lifecycle, mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class), config, 0);
        manager.start(ACCOUNT, "cluster", "admin", "Secret123");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void startupRetriesConsumedCatalogUploadBeforeRunningSql(int consumedBytes) throws Exception {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, CONTAINER);
        uploads.failures = 1;
        uploads.consumedBytes = consumedBytes;
        uploads.failure = transientFailure();

        manager.start(ACCOUNT, "retrying-cluster", "admin", "Secret123");

        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        verify(uploads.commands.get(1)).withRemotePath("/tmp");
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()));
             InputStream source = getClass().getResourceAsStream("/redshift/bootstrap-catalog.sql")) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("bootstrap-catalog.sql", entry.getName());
            assertEquals(0644, entry.getMode());
            assertArrayEquals(source.readAllBytes(), tar.readAllBytes());
            assertNull(tar.getNextEntry());
        }
        assertEquals(List.of(
                List.of("psql", "-h", "127.0.0.1", "-v", "ON_ERROR_STOP=1", "-U", "admin", "-d", "dev", "-c", "SELECT 1"),
                catalogCommand("template1"), catalogCommand("dev")), commands);
        InOrder order = inOrder(uploads.commands.get(1), docker);
        order.verify(uploads.commands.get(1)).exec();
        order.verify(docker, times(2)).execCreateCmd(CONTAINER);
    }

    @Test
    void exhaustedCatalogUploadStaysOptionalAndDoesNotRunSql() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, CONTAINER);
        uploads.failures = 6;
        uploads.consumedBytes = 73;
        uploads.failure = transientFailure();

        assertDoesNotThrow(() -> manager.bootstrapCatalog(CONTAINER, "admin", "dev"));

        assertEquals(6, uploads.commands.size());
        assertTrue(uploads.completed.isEmpty());
        assertTrue(commands.isEmpty());
    }

    @Test
    void catalogDaemonRejectionIsNotRetried() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, CONTAINER);
        uploads.failures = 6;
        uploads.failure = new NotFoundException("container missing");

        assertDoesNotThrow(() -> manager.bootstrapCatalog(CONTAINER, "admin", "dev"));

        assertEquals(1, uploads.commands.size());
        assertTrue(commands.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void snapshotUploadRetriesFreshHostResourceCommandsBeforeOneRestore(boolean stopOnError) throws Exception {
        Path dump = Files.writeString(temporary.resolve("snapshot.sql"), "SELECT 42;");
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        CopyArchiveToContainerCmd failed = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        CopyArchiveToContainerCmd succeeded = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd(CONTAINER)).thenReturn(failed, succeeded);
        doThrow(transientFailure()).when(failed).exec();

        manager.restoreSnapshot(ACCOUNT, "cluster", "admin", "analytics", dump, stopOnError);

        verify(failed).withHostResource(dump.toString());
        verify(succeeded).withHostResource(dump.toString());
        verify(succeeded).withRemotePath("/tmp");
        verify(succeeded).exec();
        List<String> expected = new ArrayList<>(List.of("psql", "-U", "admin", "-d", "analytics"));
        if (stopOnError) {
            expected.addAll(List.of("-v", "ON_ERROR_STOP=1"));
        }
        expected.addAll(List.of("-f", "/tmp/snapshot.sql"));
        assertEquals(List.of(expected), commands);
        InOrder order = inOrder(succeeded, docker);
        order.verify(succeeded).exec();
        order.verify(docker).execCreateCmd(CONTAINER);
    }

    @Test
    void exhaustedSnapshotUploadKeepsInternalFailureAndDoesNotRunSql() throws Exception {
        snapshotUploadFailure(transientFailure(), 6);
    }

    @Test
    void snapshotDaemonRejectionIsNotRetried() throws Exception {
        snapshotUploadFailure(new NotFoundException("container missing"), 1);
    }

    @Test
    void localHostArchiveFailureIsNotRetried() throws Exception {
        snapshotUploadFailure(new DockerClientException("Unable to perform tar on host resource snapshot.sql",
                new IOException("source unreadable")), 1);
    }

    @Test
    void failedSnapshotSqlIsNotRetried() throws Exception {
        Path dump = Files.writeString(temporary.resolve("snapshot.sql"), "invalid SQL;");
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 1, "", "invalid SQL");
        CopyArchiveToContainerCmd upload = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd(CONTAINER)).thenReturn(upload);

        AwsException error = assertThrows(AwsException.class,
                () -> manager.restoreSnapshot(ACCOUNT, "cluster", "admin", "dev", dump, true));

        assertEquals("InternalFailure", error.getErrorCode());
        verify(upload).exec();
        assertEquals(1, commands.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transientSnapshotSqlFailureDoesNotRepeatUploadOrSql(boolean stopOnError) throws Exception {
        Path dump = Files.writeString(temporary.resolve("snapshot.sql"), "SELECT 42;");
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        CopyArchiveToContainerCmd upload = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd(CONTAINER)).thenReturn(upload);
        when(docker.execStartCmd(anyString())).thenThrow(transientFailure());

        AwsException error = assertThrows(AwsException.class,
                () -> manager.restoreSnapshot(ACCOUNT, "cluster", "admin", "dev", dump, stopOnError));

        assertEquals("InternalFailure", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
        verify(upload).exec();
        assertEquals(1, commands.size());
    }

    @Test
    void transientCatalogSqlFailureStaysOptionalWithoutRepeatingUploadOrSql() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, CONTAINER);
        when(docker.execStartCmd(anyString())).thenThrow(transientFailure());

        assertDoesNotThrow(() -> manager.bootstrapCatalog(CONTAINER, "admin", "dev"));

        assertEquals(1, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertEquals(List.of(catalogCommand("template1")), commands);
    }

    @Test
    void absentSnapshotDoesNotUploadOrRunSql() {
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, CONTAINER);

        manager.restoreSnapshot(ACCOUNT, "cluster", "admin", "dev", null);
        manager.restoreSnapshot(ACCOUNT, "cluster", "admin", "dev", temporary.resolve("missing.sql"));

        assertTrue(uploads.commands.isEmpty());
        assertTrue(commands.isEmpty());
    }

    private void snapshotUploadFailure(RuntimeException failure, int expectedAttempts) throws Exception {
        Path dump = Files.writeString(temporary.resolve("snapshot.sql"), "SELECT 42;");
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(docker, CONTAINER, 0, "", "");
        CopyArchiveToContainerCmd upload = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(docker.copyArchiveToContainerCmd(CONTAINER)).thenReturn(upload);
        doThrow(failure).when(upload).exec();

        AwsException error = assertThrows(AwsException.class,
                () -> manager.restoreSnapshot(ACCOUNT, "cluster", "admin", "dev", dump));

        assertEquals("InternalFailure", error.getErrorCode());
        assertEquals(500, error.getHttpStatus());
        verify(upload, times(expectedAttempts)).exec();
        assertTrue(commands.isEmpty());
    }

    private static RuntimeException transientFailure() {
        return new RuntimeException(new IOException("Connection reset"));
    }

    private static List<String> catalogCommand(String database) {
        return List.of("psql", "-h", "127.0.0.1", "-v", "ON_ERROR_STOP=1", "-U", "admin",
                "-d", database, "-f", "/tmp/bootstrap-catalog.sql");
    }
}
