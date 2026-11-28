package io.floci.aws.services.kinesisanalytics.container;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.MountType;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.dns.EmbeddedDnsServer;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerExecStubs;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.floci.aws.core.common.docker.ContainerLifecycleManager.EndpointInfo;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.DockerHostResolver;
import io.floci.aws.core.common.docker.LaunchedContainerAwsEnv;
import io.floci.aws.services.kinesisanalytics.model.FlinkApplication;
import io.floci.aws.services.kinesisanalytics.model.Snapshot;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link FlinkContainerManager}'s container specifications and
 * {@code application_properties.json} construction. The JSON shape must exactly match real MSF/KDA's
 * runtime file so a real {@code KinesisAnalyticsRuntime.getApplicationProperties()} call in a user's
 * JAR finds it. The file-injection path itself was verified live against a real {@code apache/flink}
 * image rather than re-implemented here with a mocked DockerClient.
 */
class FlinkContainerManagerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> AWS_ENV = List.of(
            "AWS_DEFAULT_REGION=us-west-2",
            "AWS_REGION=us-west-2",
            "AWS_ACCESS_KEY_ID=test",
            "AWS_SECRET_ACCESS_KEY=test",
            "AWS_SESSION_TOKEN=test",
            "FLOCI_HOSTNAME=localhost.floci.io",
            "FLOCI_ENDPOINT=http://localhost.floci.io:4566",
            "AWS_ENDPOINT_URL=http://localhost.floci.io:4566");

    private ContainerLifecycleManager lifecycleManager;
    private LaunchedContainerAwsEnv awsEnv;
    private S3Service s3Service;
    private FlinkRestClient flinkRest;
    private ContainerLogStreamer logStreamer;
    private FlinkContainerManager manager;

    @BeforeEach
    void setUp() {
        EmulatorConfig.DockerConfig dockerConfig = mock(EmulatorConfig.DockerConfig.class);
        when(dockerConfig.imageRegistryBase()).thenReturn(Optional.empty());
        when(dockerConfig.resourceNamespace()).thenReturn(Optional.empty());
        when(dockerConfig.logMaxSize()).thenReturn("10m");
        when(dockerConfig.logMaxFile()).thenReturn("3");

        EmulatorConfig.KinesisAnalyticsServiceConfig kinesisAnalyticsConfig =
                mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(kinesisAnalyticsConfig.defaultImage()).thenReturn(Optional.empty());

        EmulatorConfig.ServicesConfig servicesConfig = mock(EmulatorConfig.ServicesConfig.class);
        when(servicesConfig.kinesisAnalytics()).thenReturn(kinesisAnalyticsConfig);
        when(servicesConfig.dockerNetwork()).thenReturn(Optional.of("floci-network"));

        EmulatorConfig.DnsConfig dnsConfig = mock(EmulatorConfig.DnsConfig.class);
        when(dnsConfig.containerFallbackEnabled()).thenReturn(false);

        EmulatorConfig.StorageConfig storageConfig = storageWithInitImage("busybox:stable");

        EmulatorConfig config = mock(EmulatorConfig.class);
        when(config.docker()).thenReturn(dockerConfig);
        when(config.services()).thenReturn(servicesConfig);
        when(config.dns()).thenReturn(dnsConfig);
        when(config.storage()).thenReturn(storageConfig);
        when(config.defaultRegion()).thenReturn("us-west-2");

        DockerHostResolver dockerHostResolver = mock(DockerHostResolver.class);
        when(dockerHostResolver.isLinuxHost()).thenReturn(true);
        EmbeddedDnsServer embeddedDnsServer = mock(EmbeddedDnsServer.class);
        when(embeddedDnsServer.getServerIp()).thenReturn(Optional.of("172.18.0.2"));
        ContainerBuilder containerBuilder = new ContainerBuilder(config, dockerHostResolver, embeddedDnsServer);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        // The log4j-console.properties copy is now required (a failure fails cluster startup, see
        // log4jConfigCopyFailureRollsBackTheClusterInsteadOfStartingWithTheStockLogFormat below), so
        // every test that gets as far as create() succeeding needs a working docker client for that
        // copy to land on by default.
        when(lifecycleManager.getDockerClient()).thenReturn(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        logStreamer = mock(ContainerLogStreamer.class);
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        RegionResolver regionResolver = mock(RegionResolver.class);
        s3Service = mock(S3Service.class);
        awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(eq("us-west-2"), eq(Optional.empty()))).thenReturn(AWS_ENV);
        flinkRest = mock(FlinkRestClient.class);

        manager = new FlinkContainerManager(
                containerBuilder,
                lifecycleManager,
                logStreamer,
                containerDetector,
                config,
                regionResolver,
                awsEnv,
                s3Service,
                flinkRest,
                MAPPER);
    }

    private static EmulatorConfig.StorageConfig storageWithInitImage(String initImage) {
        EmulatorConfig.EfsSharingConfig efs = mock(EmulatorConfig.EfsSharingConfig.class);
        when(efs.initImage()).thenReturn(initImage);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        when(storage.efs()).thenReturn(efs);
        return storage;
    }

    @Test
    void deleteSnapshotFilesRemovesTheSavepointInsideTheJobManager() {
        DockerClient dockerClient = mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        List<List<String>> commands = ContainerExecStubs.completeEveryExec(dockerClient, "jm-container-id", 0, "", "");
        FlinkApplication app = application("my-app");
        app.setContainerId("jm-container-id");
        Snapshot snapshot = new Snapshot();
        snapshot.setFlinkLocation("/savepoints/savepoint-1");

        manager.deleteSnapshotFiles(app, snapshot);

        assertEquals(List.of(List.of("rm", "-rf", "/savepoints/savepoint-1")), commands);
    }

    /**
     * #5664: a cluster that outlived Floci, as when Floci is killed, is kept when its JobManager and TaskManager are
     * running, the JobManager answers on the recorded REST endpoint and the recorded job still runs; shutdown then
     * removes it.
     */
    @Test
    void aClusterStillRunningItsJobIsKept() {
        FlinkApplication app = codeApplication("kept");
        survivingContainers("floci-aws-", "running");
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(true);
        when(flinkRest.jobState("http://jm:8081", "job-1")).thenReturn("RUNNING");

        assertTrue(manager.adoptRunningCluster(app));

        assertEquals("jm-survivor", app.getContainerId());
        assertEquals("tm-survivor", app.getTaskManagerContainerId());
        manager.stopAll();
        verify(lifecycleManager).stopAndRemove("tm-survivor", null);
        verify(lifecycleManager).stopAndRemove(eq("jm-survivor"), any());
    }

    /** What survives of a cluster goes under both name forms, the current one and the legacy {@code floci-} one. */
    @Test
    void removingASurvivingClusterTakesBothNameForms() {
        FlinkApplication app = codeApplication("old");
        app.setContainerId("jm-old");
        app.setTaskManagerContainerId("tm-old");

        manager.removeSurvivingCluster(app);

        // The record forgets the cluster and its job, so a later start submits the code again.
        assertNull(app.getContainerId());
        assertNull(app.getTaskManagerContainerId());
        assertNull(app.getRestEndpoint());
        assertNull(app.getFlinkJobId());
        ArgumentCaptor<String> names = ArgumentCaptor.forClass(String.class);
        verify(lifecycleManager, times(4)).removeIfExistsStrict(names.capture());
        String current = "floci-aws-kinesisanalytics-arn-aws-kinesisanalytics-us-west-2-000000000000-application-old";
        String legacy = "floci-kinesisanalytics-arn-aws-kinesisanalytics-us-west-2-000000000000-application-old";
        assertEquals(List.of(current + "-tm", legacy + "-tm", current, legacy), names.getAllValues());
    }

    /** What survives of a cluster that Docker will not remove stays named in the record, for a later attempt. */
    @Test
    void aSurvivingClusterDockerKeepsStaysNamed() {
        FlinkApplication app = codeApplication("kept");
        app.setContainerId("jm-old");
        Mockito.doThrow(new IllegalStateException("Failed to remove stale container"))
                .when(lifecycleManager).removeIfExistsStrict(Mockito.argThat(name -> !name.endsWith("-tm")));

        assertThrows(IllegalStateException.class, () -> manager.removeSurvivingCluster(app));

        assertEquals("jm-old", app.getContainerId());
        assertEquals("job-1", app.getFlinkJobId());
    }

    /**
     * A restore's own cluster that Docker will not remove stays named in the record and tracked, so shutdown still
     * removes it; the TaskManager Docker did remove is no longer tracked.
     */
    @Test
    void aClusterDockerKeepsStaysNamedAndTracked() {
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(true);
        when(flinkRest.jobState("http://jm:8081", "job-1")).thenReturn("RUNNING");
        survivingContainers("floci-aws-", "running");
        FlinkApplication restored = codeApplication("kept");
        assertTrue(manager.adoptRunningCluster(restored));
        Mockito.doThrow(new IllegalStateException("Failed to remove stale container jm-survivor"))
                .when(lifecycleManager).removeIfExistsStrict("jm-survivor");

        assertThrows(IllegalStateException.class, () -> manager.removeOwnCluster(restored));

        assertEquals("jm-survivor", restored.getContainerId());
        assertEquals("job-1", restored.getFlinkJobId());
        manager.stopAll();
        verify(lifecycleManager, times(2)).stopAndRemove(eq("jm-survivor"), any());
        verify(lifecycleManager, times(1)).stopAndRemove("tm-survivor", null);
    }

    /** A restore's TaskManager that Docker will not remove stays tracked, so shutdown still removes it. */
    @Test
    void aTaskManagerDockerKeepsStaysTracked() {
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(true);
        when(flinkRest.jobState("http://jm:8081", "job-1")).thenReturn("RUNNING");
        survivingContainers("floci-aws-", "running");
        FlinkApplication restored = codeApplication("kept-taskmanager");
        assertTrue(manager.adoptRunningCluster(restored));
        Mockito.doThrow(new IllegalStateException("Failed to remove stale container tm-survivor"))
                .when(lifecycleManager).removeIfExistsStrict("tm-survivor");

        assertThrows(IllegalStateException.class, () -> manager.removeOwnCluster(restored));

        assertEquals("tm-survivor", restored.getTaskManagerContainerId());
        manager.stopAll();
        verify(lifecycleManager, times(2)).stopAndRemove("tm-survivor", null);
    }

    /**
     * A restore removing the cluster it holds, after the application was created again under its name and started:
     * only its own containers go, and the replacement's cluster stays tracked, so shutdown still removes it.
     */
    @Test
    void removingItsOwnClusterLeavesAReplacementAlone() {
        Closeable restoredLogs = mock(Closeable.class);
        Closeable replacementLogs = mock(Closeable.class);
        when(logStreamer.attachPolled(anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(restoredLogs, replacementLogs);
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(true);
        when(flinkRest.jobState("http://jm:8081", "job-1")).thenReturn("RUNNING");
        survivingContainers("floci-aws-", "running");
        FlinkApplication restored = codeApplication("replaced");
        assertTrue(manager.adoptRunningCluster(restored));
        Container jobManager = container("jm-new", "running");
        Container taskManager = container("tm-new", "running");
        when(lifecycleManager.findByName(anyString())).thenAnswer(find ->
                Optional.of(((String) find.getArgument(0)).endsWith("-tm") ? taskManager : jobManager));
        assertTrue(manager.adoptRunningCluster(codeApplication("replaced")));

        manager.removeOwnCluster(restored);

        verify(lifecycleManager).stopAndRemove("tm-survivor", null);
        verify(lifecycleManager).stopAndRemove("jm-survivor", null);
        // The record forgets the kept cluster and its job, so a later start submits the code again.
        assertNull(restored.getContainerId());
        assertNull(restored.getTaskManagerContainerId());
        assertNull(restored.getRestEndpoint());
        assertNull(restored.getFlinkJobId());
        verify(lifecycleManager, Mockito.never()).stopAndRemove(eq("tm-new"), any());
        verify(lifecycleManager, Mockito.never()).stopAndRemove(eq("jm-new"), any());
        manager.stopAll();
        verify(lifecycleManager).stopAndRemove("tm-new", null);
        verify(lifecycleManager).stopAndRemove("jm-new", replacementLogs);
    }

    /**
     * A restore removing the cluster it started, with no other cluster since, leaves nothing of it behind: its logs
     * close with it, its code no longer waits to be submitted, and shutdown finds nothing more to remove.
     */
    @Test
    void removingItsOwnClusterClearsWhatItTracks() throws Exception {
        FlinkApplication restored = application("removed");
        restored.setCodeS3Bucket("code-bucket");
        restored.setCodeS3Key("jobs/demo.jar");
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        when(lifecycleManager.create(any())).thenReturn("jm-id").thenReturn("tm-id");
        when(lifecycleManager.startCreated(any(), any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))))
                .thenReturn(new ContainerInfo("tm-id", Map.of()));
        Closeable logs = mock(Closeable.class);
        when(logStreamer.attachPolled(anyString(), anyString(), any(), anyString(), anyString())).thenReturn(logs);
        manager.startCluster(restored);

        manager.removeOwnCluster(restored);

        verify(lifecycleManager).stopAndRemove("tm-id", null);
        verify(lifecycleManager).stopAndRemove("jm-id", logs);
        // Each removal is confirmed with Docker.
        verify(lifecycleManager).removeIfExistsStrict("tm-id");
        verify(lifecycleManager).removeIfExistsStrict("jm-id");
        // A later cluster under the same key, once it answers with slots free, has no code of this one's to submit.
        restored.setRestEndpoint("http://jm:8081");
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(true);
        when(flinkRest.totalSlots("http://jm:8081")).thenReturn(8);
        assertFalse(manager.advanceToRunning(restored));
        verify(flinkRest, Mockito.never()).uploadJar(anyString(), any());
        // A later cluster under the key, with no logs of its own, is not handed the removed cluster's at shutdown.
        when(lifecycleManager.create(any())).thenReturn("jm-2", "tm-2");
        when(lifecycleManager.startCreated(any(), any()))
                .thenReturn(new ContainerInfo("jm-2", Map.of(8081, new EndpointInfo("localhost", 49153))))
                .thenReturn(new ContainerInfo("tm-2", Map.of()));
        when(logStreamer.attachPolled(anyString(), anyString(), any(), anyString(), anyString())).thenReturn(null);
        manager.startCluster(restored);
        manager.stopAll();
        verify(lifecycleManager).stopAndRemove("jm-2", null);
        verify(lifecycleManager).stopAndRemove("jm-id", logs);
    }

    /** A cluster that no longer serves the application is not kept. */
    @Test
    void aClusterThatNoLongerServesTheApplicationIsNotKept() {
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(true);
        survivingContainers("floci-aws-", "running");
        when(flinkRest.jobState("http://jm:8081", "job-1")).thenReturn("FAILED");
        assertFalse(manager.adoptRunningCluster(codeApplication("job-failed")), "its job no longer runs");

        when(flinkRest.jobState("http://jm:8081", "job-1")).thenReturn("RUNNING");
        FlinkApplication noRest = codeApplication("no-rest");
        noRest.setRestEndpoint(null);
        FlinkApplication noJob = codeApplication("no-job");
        noJob.setFlinkJobId(null);
        clearInvocations(lifecycleManager);
        assertFalse(manager.adoptRunningCluster(noRest), "no REST endpoint is recorded");
        assertFalse(manager.adoptRunningCluster(noJob), "no job is recorded");
        // Nothing recorded to probe a cluster on, so Docker is not asked for one.
        verify(lifecycleManager, Mockito.never()).findByName(anyString());

        survivingContainers("floci-aws-", "exited");
        assertFalse(manager.adoptRunningCluster(codeApplication("stopped")), "its containers stopped");
        Container jobManager = container("jm-survivor", "running");
        when(lifecycleManager.findByName(anyString())).thenAnswer(find ->
                ((String) find.getArgument(0)).endsWith("-tm") ? Optional.empty() : Optional.of(jobManager));
        assertFalse(manager.adoptRunningCluster(codeApplication("lost-taskmanager")), "its TaskManager is gone");

        survivingContainers("floci-aws-", "running");
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(false);
        assertFalse(manager.adoptRunningCluster(codeApplication("rest-down")), "its JobManager does not answer");
        verify(lifecycleManager, Mockito.never()).stopAndRemove(anyString(), any());
    }

    /** A cluster from before the floci-aws- prefix runs in containers of the old names. */
    @Test
    void aClusterUnderTheLegacyNamesIsKept() {
        survivingContainers("floci-", "running");
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(true);
        when(flinkRest.jobState("http://jm:8081", "job-1")).thenReturn("RUNNING");

        assertTrue(manager.adoptRunningCluster(codeApplication("legacy")));
    }

    /** Once shutdown has removed the containers, no cluster starts and none is kept. */
    @Test
    void noClusterStartsOrIsKeptAfterShutdown() {
        survivingContainers("floci-aws-", "running");
        when(flinkRest.isRestUp("http://jm:8081")).thenReturn(true);
        when(flinkRest.jobState("http://jm:8081", "job-1")).thenReturn("RUNNING");

        manager.stopAll();

        assertFalse(manager.adoptRunningCluster(codeApplication("late")));
        assertThrows(FlinkContainerManager.ShuttingDownException.class,
                () -> manager.startCluster(application("late")));
        verify(lifecycleManager, Mockito.never()).create(any());
    }

    /**
     * A start still running when shutdown removes the containers, as a restore's can be while its image pulls,
     * removes the JobManager it started rather than leave it behind.
     */
    @Test
    void aJobManagerStartShutdownOvertakesIsRemoved() {
        when(lifecycleManager.create(any())).thenReturn("jm-id");
        when(lifecycleManager.startCreated(eq("jm-id"), any())).thenAnswer(start -> {
            manager.stopAll();
            return new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 32768)));
        });

        assertThrows(FlinkContainerManager.ShuttingDownException.class,
                () -> manager.startCluster(application("overtaken")));

        verify(lifecycleManager).stopAndRemove("jm-id", null);
        manager.stopAll();
        verify(lifecycleManager, times(1)).stopAndRemove(anyString(), any());
    }

    /** A TaskManager start shutdown overtakes rolls the whole cluster back. */
    @Test
    void aTaskManagerStartShutdownOvertakesRemovesTheCluster() {
        FlinkApplication app = application("overtaken-tm");
        app.setCodeS3Bucket("code-bucket");
        app.setCodeS3Key("jobs/demo.jar");
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        when(lifecycleManager.create(any())).thenReturn("jm-id").thenReturn("tm-id");
        when(lifecycleManager.startCreated(eq("jm-id"), any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))));
        when(lifecycleManager.startCreated(eq("tm-id"), any())).thenAnswer(start -> {
            manager.stopAll();
            return new ContainerInfo("tm-id", Map.of());
        });

        assertThrows(FlinkContainerManager.ShuttingDownException.class, () -> manager.startCluster(app));

        verify(lifecycleManager).stopAndRemove("tm-id", null);
        // Registered before shutdown came, so shutdown removes it as well as the rollback.
        verify(lifecycleManager, atLeastOnce()).stopAndRemove(eq("jm-id"), any());
        assertNull(app.getContainerId());
    }

    /** An application with code whose record names the cluster it ran on: its REST endpoint and job. */
    private FlinkApplication codeApplication(String name) {
        FlinkApplication app = application(name);
        app.setCodeS3Bucket("code");
        app.setCodeS3Key("app.jar");
        app.setRestEndpoint("http://jm:8081");
        app.setFlinkJobId("job-1");
        return app;
    }

    /** JobManager and TaskManager containers in the given state, under names that start with {@code prefix}. */
    private void survivingContainers(String prefix, String state) {
        Container jobManager = container("jm-survivor", state);
        Container taskManager = container("tm-survivor", state);
        when(lifecycleManager.findByName(anyString())).thenAnswer(find -> {
            String name = find.getArgument(0);
            if (!name.startsWith(prefix) || (prefix.equals("floci-") && name.startsWith("floci-aws-"))) {
                return Optional.empty();
            }
            return Optional.of(name.endsWith("-tm") ? taskManager : jobManager);
        });
    }

    private static Container container(String id, String state) {
        Container container = mock(Container.class);
        when(container.getId()).thenReturn(id);
        when(container.getState()).thenReturn(state);
        return container;
    }

    private FlinkApplication application(String name) {
        return new FlinkApplication(name,
                "arn:aws:kinesisanalytics:us-west-2:000000000000:application/" + name,
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");
    }

    @Test
    void startClusterLabelsJobManagerContainerWithResourceIdentity() {
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig services = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.KinesisAnalyticsServiceConfig kinesisAnalytics =
                Mockito.mock(EmulatorConfig.KinesisAnalyticsServiceConfig.class);
        when(config.services()).thenReturn(services);
        when(services.kinesisAnalytics()).thenReturn(kinesisAnalytics);
        when(services.dockerNetwork()).thenReturn(Optional.empty());
        when(kinesisAnalytics.defaultImage()).thenReturn(Optional.empty());
        EmulatorConfig.DockerConfig docker = Mockito.mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(docker);
        when(docker.logMaxSize()).thenReturn("10m");
        when(docker.logMaxFile()).thenReturn("3");
        EmulatorConfig.StorageConfig storage = storageWithInitImage("busybox:stable");
        when(config.storage()).thenReturn(storage);
        when(storage.hostPersistentPath()).thenReturn("floci-data");

        ContainerLifecycleManager lifecycleManager = Mockito.mock(ContainerLifecycleManager.class);
        when(lifecycleManager.create(any())).thenReturn("jm-container-id");
        when(lifecycleManager.getDockerClient()).thenReturn(mock(DockerClient.class, RETURNS_DEEP_STUBS));
        when(lifecycleManager.startCreated(any(), any())).thenReturn(
                new ContainerLifecycleManager.ContainerInfo("jm-container-id", Map.of(8081,
                        new ContainerLifecycleManager.EndpointInfo("localhost", 8081))));

        ContainerBuilder containerBuilder = Mockito.mock(ContainerBuilder.class);
        ContainerBuilder.Builder builder = Mockito.mock(ContainerBuilder.Builder.class, Mockito.RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(builder.build()).thenReturn(new ContainerSpec("test-image"));

        RegionResolver regionResolver = Mockito.mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        when(regionResolver.getDefaultRegion()).thenReturn("us-east-1");

        FlinkContainerManager manager = new FlinkContainerManager(containerBuilder, lifecycleManager,
                Mockito.mock(ContainerLogStreamer.class), Mockito.mock(ContainerDetector.class), config,
                regionResolver, Mockito.mock(LaunchedContainerAwsEnv.class), Mockito.mock(S3Service.class),
                Mockito.mock(FlinkRestClient.class), MAPPER);

        FlinkApplication app = new FlinkApplication();
        app.setApplicationName("my-app");

        manager.startCluster(app);

        verify(builder).withLabels(Map.of(
                "io.floci", "aws",
                "io.floci.service", "kinesisanalytics",
                "io.floci.resource-id", "my-app",
                "io.floci.account", "000000000000",
                "io.floci.region", "us-east-1"));
    }

    @Test
    void applicationPropertiesJsonMatchesTheRealMsfFileShape() throws Exception {
        FlinkApplication app = new FlinkApplication("demo", "arn:aws:kinesisanalytics:us-east-1:000000000000:application/demo",
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        groups.put("ProducerConfigProperties", Map.of("flink.stream.initpos", "LATEST", "aws.region", "us-west-2"));
        groups.put("ConsumerConfigProperties", Map.of("aws.region", "us-west-2"));
        app.setEnvironmentProperties(groups);

        byte[] json = manager.applicationPropertiesJson(app);
        JsonNode root = MAPPER.readTree(json);

        assertTrue(root.isArray());
        assertEquals(2, root.size());
        assertEquals("ProducerConfigProperties", root.get(0).get("PropertyGroupId").asText());
        assertEquals("LATEST", root.get(0).get("PropertyMap").get("flink.stream.initpos").asText());
        assertEquals("us-west-2", root.get(0).get("PropertyMap").get("aws.region").asText());
        assertEquals("ConsumerConfigProperties", root.get(1).get("PropertyGroupId").asText());
    }

    @Test
    void applicationPropertiesJsonIsAnEmptyArrayWhenNoPropertiesConfigured() throws Exception {
        FlinkApplication app = new FlinkApplication("bare", "arn:aws:kinesisanalytics:us-east-1:000000000000:application/bare",
                "FLINK-1_18", "arn:aws:iam::000000000000:role/x", "STREAMING");

        byte[] json = manager.applicationPropertiesJson(app);
        JsonNode root = MAPPER.readTree(json);

        // Real MSF always provides the file, even with zero property groups configured, so
        // KinesisAnalyticsRuntime.getApplicationProperties() never has to handle a missing file.
        assertTrue(root.isArray());
        assertEquals(0, root.size());
    }

    @Test
    void msfStyleLog4j2ConfigBakesInTheApplicationArnAndVersion() throws Exception {
        FlinkApplication app = new FlinkApplication("demo",
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/demo",
                "FLINK-2_3", "arn:aws:iam::000000000000:role/x", "STREAMING");
        app.setApplicationVersionId(2L);

        String config = new String(manager.msfStyleLog4j2Config(app), StandardCharsets.UTF_8);

        assertTrue(config.contains(
                "\"applicationARN\":\"%enc{arn:aws:kinesisanalytics:us-east-1:000000000000:application/demo}{JSON}\""));
        assertTrue(config.contains("\"applicationVersionId\":\"%enc{2}{JSON}\""));
        assertTrue(config.contains("\"messageSchemaVersion\":\"1\""));
        assertTrue(config.contains("appender.console.layout.type = PatternLayout"));
    }

    @Test
    void msfStyleLog4j2ConfigEscapesLog4jAndPropertiesSyntaxInTheApplicationName() throws Exception {
        // KinesisAnalyticsV2Service now rejects an ApplicationName outside AWS's own
        // [a-zA-Z0-9_.-] charset, but this stays defensive in case some other caller ever builds a
        // FlinkApplication directly: '%' would be a log4j2 conversion-specifier marker, '\' is a
        // java.util.Properties escape character (Flink loads this file with Properties-style
        // parsing), and '$' could start a log4j2 '${...}' Lookup that leaks an env var/system
        // property into every log line -- none of that may reach the pattern unescaped.
        FlinkApplication app = new FlinkApplication("100%-\\-${env:PATH}-owned",
                "arn:aws:kinesisanalytics:us-east-1:000000000000:application/100%-\\-${env:PATH}-owned",
                "FLINK-2_3", "arn:aws:iam::000000000000:role/x", "STREAMING");
        app.setApplicationVersionId(1L);

        String config = new String(manager.msfStyleLog4j2Config(app), StandardCharsets.UTF_8);

        assertTrue(config.contains(
                "\"applicationARN\":\"%enc{arn:aws:kinesisanalytics:us-east-1:000000000000:application/100%%-\\\\-{env:PATH}-owned}{JSON}\""));

        Properties parsed = new Properties();
        parsed.load(new ByteArrayInputStream(config.getBytes(StandardCharsets.UTF_8)));
        String loadedPattern = parsed.getProperty("appender.console.layout.pattern");
        assertTrue(loadedPattern.contains(
                "\"applicationARN\":\"%enc{arn:aws:kinesisanalytics:us-east-1:000000000000:application/100%%-\\-{env:PATH}-owned}{JSON}\""));
        assertFalse(loadedPattern.contains("${env:PATH}"), "the $ must not survive, or log4j2 would treat "
                + "this as a Lookup and resolve it against the environment at config-load time");
    }

    @Test
    void bareClusterInjectsAwsSdkEnvironmentAndContainerReachability() {
        FlinkApplication app = application("bare");
        when(lifecycleManager.create(any())).thenReturn("jm-id");
        when(lifecycleManager.startCreated(any(), any())).thenReturn(new ContainerInfo(
                "jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))));

        manager.startCluster(app);

        ContainerSpec jmSpec = captureCreatedSpecs().getFirst();
        assertTrue(jmSpec.env().containsAll(AWS_ENV));
        assertTrue(jmSpec.env().contains(
                "FLINK_PROPERTIES=jobmanager.rpc.address: localhost\nrest.bind-address: 0.0.0.0"));
        assertEquals(List.of("jobmanager"), jmSpec.cmd());
        assertEquals("floci-network", jmSpec.networkMode());
        assertTrue(jmSpec.extraHosts().contains("host.docker.internal:host-gateway"));
        assertTrue(jmSpec.dnsServers().contains("172.18.0.2"));
        verify(awsEnv).sdkBaselineEnv("us-west-2", Optional.empty());
    }

    @Test
    void startCluster_savepointsVolume_isChownedToFlinkUser() {
        FlinkApplication app = application("snap");
        when(lifecycleManager.create(any())).thenReturn("jm-id");
        when(lifecycleManager.startCreated(any(), any())).thenReturn(new ContainerInfo(
                "jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))));

        manager.startCluster(app);

        String volumeName = app.getDockerVolumeName();
        verify(lifecycleManager).ensureSharedVolume(volumeName, OptionalInt.of(9999), OptionalInt.of(9999),
                Optional.empty(), "busybox:stable");
        ContainerSpec jmSpec = captureCreatedSpecs().getFirst();
        assertTrue(jmSpec.mounts().stream().anyMatch(mount -> mount.getType() == MountType.VOLUME
                && volumeName.equals(mount.getSource())
                && "/opt/flink/savepoints".equals(mount.getTarget())));
    }

    @Test
    void codeClusterInjectsTheSameAwsSdkEnvironmentIntoBothFlinkProcesses() {
        FlinkApplication app = application("with-code");
        app.setCodeS3Bucket("code-bucket");
        app.setCodeS3Key("jobs/demo.jar");
        app.setParallelism(3);
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        when(lifecycleManager.create(any())).thenReturn("jm-id").thenReturn("tm-id");
        when(lifecycleManager.startCreated(any(), any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))))
                .thenReturn(new ContainerInfo("tm-id", Map.of()));

        manager.startCluster(app);

        List<ContainerSpec> specs = captureCreatedSpecs();
        ContainerSpec jmSpec = specs.get(0);
        ContainerSpec tmSpec = specs.get(1);
        assertTrue(jmSpec.env().containsAll(AWS_ENV));
        assertTrue(tmSpec.env().containsAll(AWS_ENV));
        assertEquals(List.of("jobmanager"), jmSpec.cmd());
        assertEquals(List.of("taskmanager"), tmSpec.cmd());
        assertTrue(jmSpec.env().stream().anyMatch(env -> env.startsWith("FLINK_PROPERTIES=jobmanager.rpc.address")));
        assertTrue(tmSpec.env().contains(
                "FLINK_PROPERTIES=jobmanager.rpc.address: localhost\ntaskmanager.numberOfTaskSlots: 3"));
        assertEquals("container:jm-id", tmSpec.networkMode());
        verify(awsEnv).sdkBaselineEnv("us-west-2", Optional.empty());
        // Both JVMs trust Floci's CA, as the job's SDK clients need for Floci over HTTPS.
        assertTrue(jmSpec.javaTrustStore());
        assertTrue(tmSpec.javaTrustStore());
    }

    @Test
    void jobManagerStartFailureRemovesThePartialCluster() {
        FlinkApplication app = application("jm-failure");
        when(lifecycleManager.create(any())).thenThrow(new RuntimeException("jobmanager failed"));

        assertThrows(RuntimeException.class, () -> manager.startCluster(app));

        verify(lifecycleManager, times(2)).removeIfExists("floci-aws-kinesisanalytics-arn-aws-kinesisanalytics-us-west-2-000000000000-application-jm-failure");
        verify(lifecycleManager, atLeastOnce()).removeIfExists("floci-aws-kinesisanalytics-arn-aws-kinesisanalytics-us-west-2-000000000000-application-jm-failure-tm");
        assertNull(app.getContainerId());
        assertNull(app.getTaskManagerContainerId());
    }

    @Test
    void taskManagerStartFailureStopsTheJobManagerAndClearsClusterState() {
        FlinkApplication app = application("tm-failure");
        app.setCodeS3Bucket("code-bucket");
        app.setCodeS3Key("jobs/demo.jar");
        when(s3Service.getObject("code-bucket", "jobs/demo.jar", null))
                .thenReturn(new S3Object("code-bucket", "jobs/demo.jar", new byte[]{1}, "application/java-archive"));
        when(lifecycleManager.create(any())).thenReturn("jm-id")
                .thenThrow(new RuntimeException("taskmanager failed"));
        when(lifecycleManager.startCreated(any(), any()))
                .thenReturn(new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152))));

        assertThrows(RuntimeException.class, () -> manager.startCluster(app));

        verify(lifecycleManager).stopAndRemove("jm-id", null);
        verify(lifecycleManager, atLeastOnce()).removeIfExists("floci-aws-kinesisanalytics-arn-aws-kinesisanalytics-us-west-2-000000000000-application-tm-failure-tm");
        assertNull(app.getContainerId());
        assertNull(app.getRestEndpoint());
        assertNull(app.getTaskManagerContainerId());
    }

    @Test
    void log4jConfigCopyFailureRollsBackTheClusterInsteadOfStartingWithTheStockLogFormat() {
        // Before this test, a failed copy of log4j-console.properties was only logged: startCreated()
        // still ran and startCluster() reported success, so the cluster silently kept the stock
        // (non-JSON) log format instead of the MSF-style CloudWatch schema this whole feature exists
        // to provide. copyFileIntoContainer(..., required=true) now rethrows instead, so this failure
        // takes the same create()-failure rollback path already covered above.
        FlinkApplication app = application("log4j-copy-failure");
        when(lifecycleManager.create(any())).thenReturn("jm-id");
        when(lifecycleManager.getDockerClient()).thenThrow(new RuntimeException("docker copy unavailable"));

        assertThrows(RuntimeException.class, () -> manager.startCluster(app));

        verify(lifecycleManager, times(2)).removeIfExists("floci-aws-kinesisanalytics-arn-aws-kinesisanalytics-us-west-2-000000000000-application-log4j-copy-failure");
        verify(lifecycleManager, atLeastOnce()).removeIfExists("floci-aws-kinesisanalytics-arn-aws-kinesisanalytics-us-west-2-000000000000-application-log4j-copy-failure-tm");
        verify(lifecycleManager, Mockito.never()).startCreated(any(), any());
        assertNull(app.getContainerId());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 73, 8192})
    void startupRetriesConsumedLogConfigurationUploadsBeforeStarting(int consumedBytes) throws Exception {
        FlinkApplication app = application("copy-retry");
        when(lifecycleManager.create(any())).thenReturn("jm-id");
        DockerClient docker = mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "jm-id");
        uploads.failures = 1;
        uploads.consumedBytes = consumedBytes;
        uploads.failure = new RuntimeException(new IOException("Connection reset"));
        when(lifecycleManager.startCreated(any(), any())).thenAnswer(invocation -> {
            assertEquals(1, uploads.completed.size(), "configuration must be uploaded before starting");
            return new ContainerInfo("jm-id", Map.of(8081, new EndpointInfo("localhost", 49152)));
        });
        FlinkContainerManager startup = spy(manager);
        doAnswer(invocation -> {
            manager.copyFileIntoContainer(invocation.getArgument(0), invocation.getArgument(1),
                    invocation.getArgument(2), invocation.getArgument(3), invocation.getArgument(4), 0);
            return null;
        }).when(startup).copyFileIntoContainer(anyString(), anyString(), anyString(), any(), anyBoolean(), anyLong());

        startup.startCluster(app);

        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        uploads.commands.forEach(command -> verify(command).withRemotePath("/opt/flink/conf"));
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("log4j-console.properties", entry.getName());
            assertEquals(0644, entry.getMode());
            assertArrayEquals(manager.msfStyleLog4j2Config(app), tar.readAllBytes());
            assertNull(tar.getNextEntry());
        }
        verify(lifecycleManager).startCreated(eq("jm-id"), any());
        assertEquals("jm-id", app.getContainerId());
    }

    @Test
    void applicationPropertiesRetryKeepsTheNestedPathAndPayload() throws Exception {
        DockerArchiveUploadFixture uploads = failingUpload(new RuntimeException(new IOException("Broken pipe")), 2);
        byte[] content = manager.applicationPropertiesJson(application("properties-retry"));

        manager.copyFileIntoContainer("jm-id", "/etc", "flink/application_properties.json", content, false, 0);

        assertEquals(3, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.get(0), uploads.streams.get(1));
        assertNotSame(uploads.streams.get(1), uploads.streams.get(2));
        uploads.commands.forEach(command -> verify(command).withRemotePath("/etc"));
        try (TarArchiveInputStream tar = new TarArchiveInputStream(
                new ByteArrayInputStream(uploads.completed.getFirst()))) {
            TarArchiveEntry entry = tar.getNextEntry();
            assertEquals("flink/application_properties.json", entry.getName());
            assertEquals(0644, entry.getMode());
            assertArrayEquals(content, tar.readAllBytes());
            assertNull(tar.getNextEntry());
        }
    }

    @Test
    void requiredCopyExhaustsSixAttemptsBeforePropagatingTheFailure() {
        DockerArchiveUploadFixture uploads = failingUpload(new RuntimeException(new IOException("Broken pipe")), 6);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> manager.copyFileIntoContainer("jm-id", "/opt/flink/conf", "log4j-console.properties",
                        new byte[]{1, 2, 3}, true, 0));

        assertEquals(6, uploads.commands.size());
        assertTrue(uploads.completed.isEmpty());
        assertTrue(failure.getMessage().contains("log4j-console.properties"));
        assertSame(uploads.failure, failure.getCause().getCause());
    }

    @Test
    void optionalCopyStillSuppressesFailureAfterExhaustingRetries() {
        DockerArchiveUploadFixture uploads = failingUpload(new RuntimeException(new IOException("Broken pipe")), 6);

        manager.copyFileIntoContainer("jm-id", "/etc", "flink/application_properties.json",
                new byte[]{1}, false, 0);

        assertEquals(6, uploads.commands.size());
        assertTrue(uploads.completed.isEmpty());
    }

    @Test
    void daemonRejectionIsNotRetried() {
        DockerArchiveUploadFixture uploads = failingUpload(new NotFoundException("container missing"), 6);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> manager.copyFileIntoContainer("jm-id", "/etc", "flink/application_properties.json",
                        new byte[]{1}, true, 0));

        assertEquals(1, uploads.commands.size());
        assertSame(uploads.failure, failure.getCause().getCause());
    }

    @Test
    void missingContainerDoesNotAttemptAnUpload() {
        manager.copyFileIntoContainer(null, "/etc", "flink/application_properties.json", new byte[]{1}, true, 0);

        verify(lifecycleManager, Mockito.never()).getDockerClient();
    }

    private DockerArchiveUploadFixture failingUpload(RuntimeException failure, int failures) {
        DockerClient docker = mock(DockerClient.class);
        when(lifecycleManager.getDockerClient()).thenReturn(docker);
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(docker, "jm-id");
        uploads.failure = failure;
        uploads.failures = failures;
        uploads.consumedBytes = 73;
        return uploads;
    }

    private List<ContainerSpec> captureCreatedSpecs() {
        ArgumentCaptor<ContainerSpec> captor = ArgumentCaptor.forClass(ContainerSpec.class);
        verify(lifecycleManager, atLeastOnce()).create(captor.capture());
        return captor.getAllValues();
    }
}
