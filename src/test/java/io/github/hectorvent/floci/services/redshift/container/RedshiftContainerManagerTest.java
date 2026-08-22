package io.github.hectorvent.floci.services.redshift.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.ExecStartCmd;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
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

import java.io.Closeable;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
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

        manager = new RedshiftContainerManager(
                containerBuilder,
                lifecycleManager,
                logStreamer,
                containerDetector,
                config
        );
    }

    @Test
    void testTakeSnapshotContainerNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.takeSnapshot("non-existent-cluster", "admin", "dev"));
        assertEquals("ClusterNotFound", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void testRestoreSnapshotContainerNotFound() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.restoreSnapshot("non-existent-cluster", "admin", "dev", "CREATE TABLE test;"));
        assertEquals("ClusterNotFound", ex.getErrorCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void testCreateSnapshotNullCluster() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.createSnapshot(null));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void testRestoreSnapshotNullCluster() {
        AwsException ex = assertThrows(AwsException.class, () ->
                manager.restoreSnapshot((Cluster) null, "CREATE TABLE test;"));
        assertEquals("InvalidParameterValue", ex.getErrorCode());
        assertEquals(400, ex.getHttpStatus());
    }

    @Test
    void testTakeSnapshotSuccess() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        RedshiftContainerHandle handle = manager.start("test-cluster", "admin", "pass");
        assertNotNull(handle);
        assertEquals("cont-123", handle.getContainerId());
        assertTrue(manager.getContainer("test-cluster").isPresent());

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
            byte[] output = "-- PostgreSQL dump\nCREATE TABLE foo (id int);\n".getBytes(StandardCharsets.UTF_8);
            adapter.onNext(new Frame(StreamType.STDOUT, output));
            adapter.onComplete();
            return adapter;
        });
        when(dockerClient.execStartCmd("exec-1")).thenReturn(startCmd);

        InspectExecCmd inspectCmd = mock(InspectExecCmd.class);
        InspectExecResponse inspectResponse = mock(InspectExecResponse.class);
        when(inspectResponse.getExitCodeLong()).thenReturn(0L);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        when(dockerClient.inspectExecCmd("exec-1")).thenReturn(inspectCmd);

        String dump = manager.takeSnapshot("test-cluster", "admin", "dev");
        assertTrue(dump.contains("PostgreSQL dump"));
        assertTrue(dump.contains("CREATE TABLE foo"));
    }

    @Test
    void testTakeSnapshotFailureExitCode() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.start("test-cluster", "admin", "pass");

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
                manager.takeSnapshot("test-cluster", "admin", "dev"));
        assertEquals("InternalFailure", ex.getErrorCode());
        assertEquals(500, ex.getHttpStatus());
    }

    @Test
    void testRestoreSnapshotEmptyDump() {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.start("test-cluster", "admin", "pass");

        // Should return cleanly without touching dockerClient
        manager.restoreSnapshot("test-cluster", "admin", "dev", "");
        manager.restoreSnapshot("test-cluster", "admin", "dev", null);
    }

    @Test
    void testRestoreSnapshotSuccess() throws Exception {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.start("test-cluster", "admin", "pass");

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

        manager.restoreSnapshot("test-cluster", "admin", "dev", "CREATE TABLE foo (id int);");
        verify(dockerClient).copyArchiveToContainerCmd("cont-123");
        verify(dockerClient).execCreateCmd("cont-123");
    }

    @Test
    void testStopRemovesContainer() {
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, org.mockito.Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        ContainerInfo info = new ContainerInfo("cont-123", Map.of(5432, new EndpointInfo("localhost", 5432)));
        when(lifecycleManager.createAndStart(any())).thenReturn(info);

        manager.start("test-cluster", "admin", "pass");
        assertTrue(manager.getContainer("test-cluster").isPresent());

        manager.stop("test-cluster");
        assertTrue(manager.getContainer("test-cluster").isEmpty());
        verify(lifecycleManager).removeIfExists("floci-redshift-test-cluster");
    }
}
