package io.github.hectorvent.floci.services.redshift.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.services.redshift.model.Cluster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import com.github.dockerjava.api.command.CopyArchiveFromContainerCmd;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedshiftContainerManagerTest {

    private ContainerBuilder containerBuilder;
    private ContainerLifecycleManager lifecycleManager;
    private ContainerLogStreamer logStreamer;
    private ContainerDetector containerDetector;
    private EmulatorConfig config;
    private DockerClient dockerClient;
    private RedshiftContainerManager manager;

    @BeforeEach
    void setUp() {
        containerBuilder = mock(ContainerBuilder.class);
        lifecycleManager = mock(ContainerLifecycleManager.class);
        logStreamer = mock(ContainerLogStreamer.class);
        containerDetector = mock(ContainerDetector.class);
        config = mock(EmulatorConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        dockerClient = mock(DockerClient.class);

        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        when(config.services().redshift().imageVersion()).thenReturn("postgres:16-alpine");
        when(config.services().redshift().dockerNetwork()).thenReturn(Optional.empty());

        // Default mock for execCreateCmd/execStartCmd/inspectExecCmd to make waitForReady succeed instantly
        ExecCreateCmd defaultCreateCmd = mock(ExecCreateCmd.class, org.mockito.Mockito.RETURNS_SELF);
        ExecCreateCmdResponse defaultCreateResponse = mock(ExecCreateCmdResponse.class);
        when(defaultCreateResponse.getId()).thenReturn("exec-default");
        when(defaultCreateCmd.exec()).thenReturn(defaultCreateResponse);
        when(dockerClient.execCreateCmd(anyString())).thenReturn(defaultCreateCmd);

        ExecStartCmd defaultStartCmd = mock(ExecStartCmd.class);
        when(defaultStartCmd.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback.Adapter<Frame> adapter = invocation.getArgument(0);
            adapter.onComplete();
            return adapter;
        });
        when(dockerClient.execStartCmd(anyString())).thenReturn(defaultStartCmd);

        InspectExecCmd defaultInspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse defaultInspectResponse = mock(InspectExecResponse.class);
        when(defaultInspectResponse.getExitCodeLong()).thenReturn(0L);
        when(defaultInspectCmd.exec()).thenReturn(defaultInspectResponse);
        when(dockerClient.inspectExecCmd(anyString())).thenReturn(defaultInspectCmd);

        manager = new RedshiftContainerManager(
                containerBuilder,
                lifecycleManager,
                logStreamer,
                containerDetector,
                config
        );
    }

    private static final String ACCOUNT_ID = "111111111111";

    @Test
    void testTakeSnapshotContainerNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.takeSnapshot(ACCOUNT_ID, "non-existent-cluster", "admin", "dev", Path.of("dummy.sql")));
        assertEquals("ClusterNotFound", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void testRestoreSnapshotContainerNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.restoreSnapshot(ACCOUNT_ID, "non-existent-cluster", "admin", "dev", Path.of("dummy.sql")));
        assertEquals("ClusterNotFound", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void testCreateSnapshotNullCluster() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.createSnapshot(ACCOUNT_ID, null, Path.of("dummy.sql")));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void testRestoreSnapshotNullCluster() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.restoreSnapshot(ACCOUNT_ID, (Cluster) null, Path.of("dummy.sql")));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void testTakeSnapshotSuccess() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        RedshiftContainerHandle handle = manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");
        assertNotNull(handle);
        assertEquals("cont-123", handle.getContainerId());
        assertTrue(manager.getContainer(ACCOUNT_ID, "test-cluster").isPresent());

        // Mock docker exec for pg_dump
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, org.mockito.Mockito.RETURNS_SELF);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn("exec-1");
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd("cont-123")).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback.Adapter<Frame> adapter = invocation.getArgument(0);
            adapter.onComplete();
            return adapter;
        });
        when(dockerClient.execStartCmd("exec-1")).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd("exec-1")).thenReturn(inspectCmd);

        // mock copyArchiveFromContainerCmd
        CopyArchiveFromContainerCmd copyCmd = mock(CopyArchiveFromContainerCmd.class, org.mockito.Mockito.RETURNS_SELF);
        byte[] tarBytes = "-- PostgreSQL dump\nCREATE TABLE foo (id int);\n".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tar = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bos)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry("dump.sql");
            entry.setSize(tarBytes.length);
            tar.putArchiveEntry(entry);
            tar.write(tarBytes);
            tar.closeArchiveEntry();
        }
        when(copyCmd.exec()).thenReturn(new ByteArrayInputStream(bos.toByteArray()));
        when(dockerClient.copyArchiveFromContainerCmd("cont-123", "/tmp/dump.sql")).thenReturn(copyCmd);

        Path tempFile = Files.createTempFile("test-take-snapshot", ".sql");
        try {
            manager.takeSnapshot(ACCOUNT_ID, "test-cluster", "admin", "dev",tempFile);
            String dump = Files.readString(tempFile);
            assertTrue(dump.contains("PostgreSQL dump"));
            assertTrue(dump.contains("CREATE TABLE foo"));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testTakeSnapshotFailureExitCode() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");

        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, org.mockito.Mockito.RETURNS_SELF);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn("exec-fail");
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd("cont-123")).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback.Adapter<Frame> adapter = invocation.getArgument(0);
            byte[] err = "pg_dump: error: connection failed".getBytes(StandardCharsets.UTF_8);
            adapter.onNext(new Frame(StreamType.STDERR, err));
            adapter.onComplete();
            return adapter;
        });
        when(dockerClient.execStartCmd("exec-fail")).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(1L);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd("exec-fail")).thenReturn(inspectCmd);

        AwsException ex = assertThrows(AwsException.class, () ->
                manager.takeSnapshot(ACCOUNT_ID, "test-cluster", "admin", "dev",Path.of("dummy.sql")));
        assertEquals("InternalFailure", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
    }

    @Test
    void testRestoreSnapshotEmptyDump() {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");

        // Should return cleanly without touching dockerClient
        manager.restoreSnapshot(ACCOUNT_ID, "test-cluster", "admin", "dev",Path.of("non-existent-dump.sql"));
        manager.restoreSnapshot(ACCOUNT_ID, "test-cluster", "admin", "dev",(Path) null);
    }

    @Test
    void testRestoreSnapshotSuccess() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");

        // Mock copyArchiveToContainerCmd
        CopyArchiveToContainerCmd copyCmd = mock(CopyArchiveToContainerCmd.class, org.mockito.Mockito.RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("cont-123")).thenReturn(copyCmd);

        // Mock docker exec for psql
        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, org.mockito.Mockito.RETURNS_SELF);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn("exec-restore");
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd("cont-123")).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback.Adapter<Frame> adapter = invocation.getArgument(0);
            adapter.onComplete();
            return adapter;
        });
        when(dockerClient.execStartCmd("exec-restore")).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd("exec-restore")).thenReturn(inspectCmd);

        Path tempFile = Files.createTempFile("test-restore-snapshot", ".sql");
        try {
            manager.restoreSnapshot(ACCOUNT_ID, "test-cluster", "admin", "dev",tempFile);
            verify(dockerClient).copyArchiveToContainerCmd("cont-123");
            verify(dockerClient, org.mockito.Mockito.times(2)).execCreateCmd("cont-123");
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    void testStopRemovesContainer() {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");
        assertTrue(manager.getContainer(ACCOUNT_ID, "test-cluster").isPresent());

        manager.stop(ACCOUNT_ID, "test-cluster");
        assertTrue(manager.getContainer(ACCOUNT_ID, "test-cluster").isEmpty());
        verify(lifecycleManager).removeIfExists("floci-redshift-" + ACCOUNT_ID + "-test-cluster");
    }

    @Test
    void testSameClusterIdentifierAcrossAccountsDoesNotCollide() {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo infoA = new ContainerInfo("cont-account-a", Map.of(5432, new EndpointInfo("localhost", 5432)));
        ContainerInfo infoB = new ContainerInfo("cont-account-b", Map.of(5432, new EndpointInfo("localhost", 5433)));
        when(lifecycleManager.createAndStart(any())).thenReturn(infoA, infoB);

        String otherAccountId = "222222222222";
        manager.start(ACCOUNT_ID, "shared-id", "admin", "pass");
        manager.start(otherAccountId, "shared-id", "admin", "pass");

        // Both accounts keep their own running container despite the shared cluster identifier
        assertTrue(manager.getContainer(ACCOUNT_ID, "shared-id").isPresent());
        assertTrue(manager.getContainer(otherAccountId, "shared-id").isPresent());
        assertEquals("cont-account-a", manager.getContainer(ACCOUNT_ID, "shared-id").get().getContainerId());
        assertEquals("cont-account-b", manager.getContainer(otherAccountId, "shared-id").get().getContainerId());
    }

    @Test
    void testAdoptOrStartAdoptsExistingContainerInsteadOfRecreatingIt() {
        String containerName = "floci-redshift-" + ACCOUNT_ID + "-test-cluster";
        Container existing = mock(Container.class);
        when(existing.getId()).thenReturn("cont-existing");
        when(lifecycleManager.findByName(containerName)).thenReturn(Optional.of(existing));
        ContainerInfo adopted = new ContainerInfo("cont-existing", Map.of(5432, new EndpointInfo("localhost", 6000)));
        when(lifecycleManager.adopt(eq("cont-existing"), any())).thenReturn(adopted);

        RedshiftContainerHandle handle = manager.adoptOrStart(ACCOUNT_ID, "test-cluster", "admin", "pass");

        assertEquals("cont-existing", handle.getContainerId());
        assertEquals(6000, handle.getPort());
        assertTrue(manager.getContainer(ACCOUNT_ID, "test-cluster").isPresent());
        // Data lives only in the container's writable layer (no volume) — recreating it
        // would silently discard it, so a container found by name must never be recreated.
        verify(lifecycleManager, never()).createAndStart(any());
    }

    @Test
    void testAdoptOrStartFallsBackToStartWhenNoExistingContainer() {
        String containerName = "floci-redshift-" + ACCOUNT_ID + "-test-cluster";
        when(lifecycleManager.findByName(containerName)).thenReturn(Optional.empty());
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-fresh", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        RedshiftContainerHandle handle = manager.adoptOrStart(ACCOUNT_ID, "test-cluster", "admin", "pass");

        assertEquals("cont-fresh", handle.getContainerId());
        verify(lifecycleManager, never()).adopt(any(), any());
    }

    @Test
    void testAlterUserPasswordSuccess() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);
        manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");

        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, org.mockito.Mockito.RETURNS_SELF);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn("exec-alter");
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd("cont-123")).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback.Adapter<Frame> adapter = invocation.getArgument(0);
            adapter.onComplete();
            return adapter;
        });
        when(dockerClient.execStartCmd("exec-alter")).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd("exec-alter")).thenReturn(inspectCmd);

        manager.alterUserPassword(ACCOUNT_ID, "test-cluster", "admin", "NewSecret1");

        verify(dockerClient, org.mockito.Mockito.times(2)).execCreateCmd("cont-123");
    }

    @Test
    void testAlterUserPasswordContainerNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.alterUserPassword(ACCOUNT_ID, "non-existent-cluster", "admin", "new-secret"));
        assertEquals("ClusterNotFound", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void testAlterUserPasswordInvalidUsername() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);
        manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");

        // Test SQL injection attempt with semicolon
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.alterUserPassword(ACCOUNT_ID, "test-cluster", "postgres; DROP TABLE some_table; --", "new-secret"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());

        // Test SQL injection attempt with space
        ex = assertThrows(AwsException.class, () ->
                manager.alterUserPassword(ACCOUNT_ID, "test-cluster", "admin user", "new-secret"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void testAlterUserPasswordInvalidPassword() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);
        manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");

        // Forbidden characters: ', ", \, / and @ (AWS ModifyCluster rejects the same set)
        for (String bad : new String[]{"Pass'word1", "Pass\"word1", "Pass\\word1", "Pass/word1", "Pass@word1"}) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    manager.alterUserPassword(ACCOUNT_ID, "test-cluster", "admin", bad));
            assertEquals("InvalidParameterValue", ex.getErrorCode());
            assertEquals(400, ex.getHttpStatus());
        }

        // Too short (< 8) and too long (> 64)
        for (String bad : new String[]{"Ab1cde", "A1" + "a".repeat(63)}) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    manager.alterUserPassword(ACCOUNT_ID, "test-cluster", "admin", bad));
            assertEquals("InvalidParameterValue", ex.getErrorCode());
            assertEquals(400, ex.getHttpStatus());
        }

        // Missing a required class: no uppercase, no lowercase, no digit
        for (String bad : new String[]{"lowercase1", "UPPERCASE1", "NoDigitsHere"}) {
            AwsException ex = assertThrows(AwsException.class, () ->
                    manager.alterUserPassword(ACCOUNT_ID, "test-cluster", "admin", bad));
            assertEquals("InvalidParameterValue", ex.getErrorCode());
            assertEquals(400, ex.getHttpStatus());
        }
    }

    @Test
    void testAlterUserPasswordExecFailure() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);
        manager.start(ACCOUNT_ID, "test-cluster", "admin", "pass");

        ExecCreateCmd createCmd = mock(ExecCreateCmd.class, org.mockito.Mockito.RETURNS_SELF);
        ExecCreateCmdResponse createResponse = mock(ExecCreateCmdResponse.class);
        when(createResponse.getId()).thenReturn("exec-fail");
        when(createCmd.exec()).thenReturn(createResponse);
        when(dockerClient.execCreateCmd("cont-123")).thenReturn(createCmd);

        ExecStartCmd startCmd = mock(ExecStartCmd.class);
        when(startCmd.exec(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            ResultCallback.Adapter<Frame> adapter = invocation.getArgument(0);
            byte[] err = "ERROR: role \"admin\" does not exist".getBytes(StandardCharsets.UTF_8);
            adapter.onNext(new Frame(StreamType.STDERR, err));
            adapter.onComplete();
            return adapter;
        });
        when(dockerClient.execStartCmd("exec-fail")).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(1L);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd("exec-fail")).thenReturn(inspectCmd);

        AwsException ex = assertThrows(AwsException.class, () ->
                manager.alterUserPassword(ACCOUNT_ID, "test-cluster", "admin", "NewSecret1"));
        assertEquals("InternalFailure", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
    }
}
