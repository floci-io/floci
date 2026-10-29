package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.LogConfig;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager.ContainerInfo;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.ContainerSpec;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.core.common.docker.RetryingTarCopier;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.PortMapping;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import io.github.hectorvent.floci.testing.DockerArchiveUploadFixture;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.MockMakers;
import org.mockito.MockedStatic;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class EcsContainerManagerFirelensTest {

    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private S3Service s3Service;
    private ContainerLogStreamer logStreamer;
    private DockerClient dockerClient;
    private CopyArchiveToContainerCmd copyCmd;
    private InputStream uploadStream;
    private byte[] uploadedArchive;
    private EcsContainerManager manager;
    private EmulatorConfig config;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("image"));
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);
        when(containerBuilder.resolveImage(anyString())).thenAnswer(invocation -> invocation.getArgument(0));

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.resolveImageForLaunch(any(), any(), any()))
                .thenAnswer(invocation -> new LaunchImage(invocation.getArgument(0), null));
        when(lifecycleManager.removeVolume(anyString())).thenReturn(true);
        when(lifecycleManager.createAndStartEphemeral(any(), any())).thenReturn(new ContainerInfo("app-id", Map.of()));
        when(lifecycleManager.create(any(), any())).thenReturn("router-id");
        when(lifecycleManager.startCreated(anyString(), any())).thenReturn(new ContainerInfo("router-id", Map.of()));

        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        InspectVolumeCmd inspectVolumeCmd = mock(InspectVolumeCmd.class);
        InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
        when(dockerClient.inspectVolumeCmd(anyString())).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenReturn(volume);
        when(volume.getMountpoint()).thenReturn("/var/lib/docker/volumes/floci-ecs-firelens-abc123/_data");
        copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(copyCmd.withTarInputStream(any(InputStream.class))).thenAnswer(invocation -> {
            uploadStream = invocation.getArgument(0);
            return copyCmd;
        });
        doAnswer(invocation -> {
            uploadedArchive = uploadStream.readAllBytes();
            return null;
        }).when(copyCmd).exec();
        when(dockerClient.copyArchiveToContainerCmd("router-id")).thenReturn(copyCmd);

        logStreamer = mock(ContainerLogStreamer.class);
        when(logStreamer.generateLogStreamName(anyString())).thenReturn("stream");
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        RegionResolver regionResolver = mock(RegionResolver.class);
        LaunchedContainerAwsEnv awsEnv = mock(LaunchedContainerAwsEnv.class);
        when(awsEnv.sdkBaselineEnv(any(), any())).thenReturn(List.of());
        when(awsEnv.flociEndpoint()).thenReturn("http://host.docker.internal:4566");
        EcrRegistryManager ecrRegistryManager = mock(EcrRegistryManager.class);
        when(ecrRegistryManager.rewriteImageUri(anyString())).thenAnswer(inv -> inv.getArgument(0));

        s3Service = mock(S3Service.class);
        manager = new EcsContainerManager(containerBuilder, lifecycleManager, logStreamer,
                containerDetector, config, regionResolver, awsEnv, mock(SsmService.class),
                mock(SecretsManagerService.class), s3Service, ecrRegistryManager, new HostVolumePolicy(config));
    }

    @Test
    void startsRouterFirstAndWiresAwsfirelensToUnixSocket() {
        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("amazon/aws-for-fluent-bit:stable");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of()));
        router.setLogConfiguration(new LogConfiguration("awslogs",
                Map.of("awslogs-group", "/ecs/firelens-family", "awslogs-stream-prefix", "firelens"), null));

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens",
                Map.of("Name", "cloudwatch", "region", "us-east-1"), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("firelens-family");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app, router));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/test-cluster");

        EcsTaskHandle handle = manager.startTask(task, taskDef, null, "us-east-1");

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).create(any(ContainerSpec.class), any());
        order.verify(lifecycleManager).startCreated(anyString(), any());
        order.verify(lifecycleManager).createAndStartEphemeral(any(ContainerSpec.class), any());

        ArgumentCaptor<LogConfig> logConfig = ArgumentCaptor.forClass(LogConfig.class);
        verify(builder).withLogConfig(logConfig.capture());
        assertEquals(LogConfig.LoggingType.FLUENTD, logConfig.getValue().getType());
        assertEquals("unix:///var/lib/docker/volumes/floci-ecs-firelens-abc123/_data/fluent.sock",
                logConfig.getValue().getConfig().get("fluentd-address"));
        assertEquals("app-firelens-abc123", logConfig.getValue().getConfig().get("tag"));
        assertEquals("true", logConfig.getValue().getConfig().get("fluentd-async"));
        assertTrue(logConfig.getValue().getConfig().get("fluentd-async-connect") == null,
                "fluentd-async-connect was removed by Docker 28");

        verify(logStreamer).attachForAccount(eq("000000000000"), eq("router-id"), anyString(), anyString(), anyString(),
                anyString());
        verify(logStreamer, never()).attachForAccount(anyString(), eq("app-id"), anyString(), anyString(), anyString(),
                anyString());
        verify(logStreamer, never()).attachConsoleOnly(eq("app-id"), anyString());
        assertTrue(handle.getFirelensVolumeName().contains("firelens"));

        verify(copyCmd).withRemotePath("/fluent-bit/etc");
        verify(copyCmd).withTarInputStream(any(InputStream.class));
        assertTrue(readFluentBitConf(new ByteArrayInputStream(uploadedArchive)).contains(
                "    Endpoint http://host.docker.internal:4566"));
    }

    @Test
    void startsFluentdRouterAndWritesFluentConf() {
        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("fluent/fluentd:v1.16");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentd", Map.of()));

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens",
                Map.of("@type", "stdout"), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("firelens-fluentd");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.bridge);
        taskDef.setContainerDefinitions(List.of(app, router));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/test-cluster");

        manager.startTask(task, taskDef, null, "us-east-1");

        InOrder order = inOrder(lifecycleManager);
        order.verify(lifecycleManager).create(any(ContainerSpec.class), any());
        order.verify(lifecycleManager).startCreated(anyString(), any());
        order.verify(lifecycleManager).createAndStartEphemeral(any(ContainerSpec.class), any());

        ArgumentCaptor<LogConfig> logConfig = ArgumentCaptor.forClass(LogConfig.class);
        verify(builder).withLogConfig(logConfig.capture());
        assertEquals(LogConfig.LoggingType.FLUENTD, logConfig.getValue().getType());
        assertEquals("unix:///var/lib/docker/volumes/floci-ecs-firelens-abc123/_data/fluent.sock",
                logConfig.getValue().getConfig().get("fluentd-address"));

        verify(copyCmd).withRemotePath("/fluentd/etc");
        verify(copyCmd).withTarInputStream(any(InputStream.class));
        String conf = readFluentBitConf(new ByteArrayInputStream(uploadedArchive));
        assertTrue(conf.contains("@type unix"));
        assertTrue(conf.contains("<match app-firelens**>"));
        assertTrue(conf.contains("@type stdout"));
        assertTrue(!conf.contains("Endpoint"));
    }


    @Test
    void writesS3ExternalConfigBesideTheGeneratedFluentBitConfig() {
        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("amazon/aws-for-fluent-bit:stable");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of(
                "config-file-type", "s3",
                "config-file-value", "arn:aws:s3:::firelens-configs/extra.conf")));

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens",
                Map.of("Name", "cloudwatch", "region", "us-east-1"), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("firelens-s3");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(app, router));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/test-cluster");

        String extra = "[OUTPUT]\n    Name stdout\n    Match app-firelens*\n";
        S3Object object = new S3Object();
        object.setData(extra.getBytes(StandardCharsets.UTF_8));
        when(s3Service.getObject("firelens-configs", "extra.conf")).thenReturn(object);

        manager.startTask(task, taskDef, null, "us-east-1");

        verify(s3Service).getObject("firelens-configs", "extra.conf");
        verify(copyCmd).withRemotePath("/fluent-bit/etc");
        verify(copyCmd).withTarInputStream(any(InputStream.class));
        Map<String, String> files = readFirelensArchive(new ByteArrayInputStream(uploadedArchive));
        assertEquals(2, files.size(), files::toString);
        assertTrue(files.get("fluent-bit.conf").contains(
                "@INCLUDE /fluent-bit/etc/external.conf"), files.get("fluent-bit.conf"));
        assertEquals(extra, files.get("external.conf"));
    }

    @Test
    void fluentdS3ExternalConfigUsesTheFluentdPath() {
        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("fluent/fluentd:v1.16");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentd", Map.of(
                "config-file-type", "s3",
                "config-file-value", "arn:aws:s3:::firelens-configs/extra.conf")));

        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of("@type", "stdout"), null));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("firelens-s3-fluentd");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.bridge);
        taskDef.setContainerDefinitions(List.of(app, router));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/test-cluster");

        S3Object object = new S3Object();
        object.setData("<match **>\n    @type stdout\n</match>\n".getBytes(StandardCharsets.UTF_8));
        when(s3Service.getObject("firelens-configs", "extra.conf")).thenReturn(object);

        manager.startTask(task, taskDef, null, "us-east-1");

        verify(copyCmd).withRemotePath("/fluentd/etc");
        verify(copyCmd).withTarInputStream(any(InputStream.class));
        Map<String, String> files = readFirelensArchive(new ByteArrayInputStream(uploadedArchive));
        assertTrue(files.get("fluent.conf").contains(
                "@include /fluentd/etc/external.conf"), files.get("fluent.conf"));
        assertTrue(files.containsKey("external.conf"), files::toString);
    }

    @Test
    void missingS3ConfigFailsTheTaskBeforeAnyContainerIsCreated() {
        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("amazon/aws-for-fluent-bit:stable");
        router.setFirelensConfiguration(new FirelensConfiguration("fluentbit", Map.of(
                "config-file-type", "s3",
                "config-file-value", "arn:aws:s3:::firelens-configs/missing.conf")));

        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("firelens-s3-missing");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(List.of(router));

        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/test-cluster");

        when(s3Service.getObject("firelens-configs", "missing.conf"))
                .thenThrow(new AwsException("NoSuchKey", "The specified key does not exist.", 404));

        AwsException failure = assertThrows(AwsException.class,
                () -> manager.startTask(task, taskDef, null, "us-east-1"));
        assertEquals("ResourceInitializationError", failure.getErrorCode());
        assertEquals("Unable to download firelens s3 config file: unable to download s3 config "
                + "missing.conf from bucket firelens-configs: The specified key does not exist.",
                failure.getMessage());
        verify(lifecycleManager, never()).create(any(ContainerSpec.class), any());
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
    }

    @Test
    void rejectsAwsfirelensApplicationWithoutRouter() {
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens", Map.of(), null));

        AwsException failure = assertThrows(AwsException.class,
                () -> manager.startTask(task(), taskDefinition(List.of(app)), null, "us-east-1"));

        assertEquals("ClientException", failure.getErrorCode());
        assertEquals("awsfirelens log driver requires a firelensConfiguration container",
                failure.getMessage());
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
    }

    @Test
    void rejectsTwoFirelensRouters() {
        AwsException failure = assertThrows(AwsException.class, () -> manager.startTask(
                task(),
                taskDefinition(List.of(
                        router("fluentbit", List.of()),
                        router("fluentd", List.of()))),
                null, "us-east-1"));

        assertEquals("ClientException", failure.getErrorCode());
        assertEquals("A task definition can have only one FireLens log router.", failure.getMessage());
        verify(lifecycleManager, never()).create(any(ContainerSpec.class), any());
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
    }

    @Test
    void rejectsFirelensRouterExposingPort24224() {
        AwsException failure = assertThrows(AwsException.class, () -> manager.startTask(
                task(),
                taskDefinition(List.of(router("fluentbit", List.of(new PortMapping(24224, 0, "tcp"))))),
                null, "us-east-1"));

        assertEquals("ClientException", failure.getErrorCode());
        assertEquals("FireLens port 24224 must not be exposed.", failure.getMessage());
        verify(lifecycleManager, never()).create(any(ContainerSpec.class), any());
    }

    @Test
    void rejectsUnsupportedFirelensRouterType() {
        AwsException failure = assertThrows(AwsException.class, () -> manager.startTask(
                task(),
                taskDefinition(List.of(router("fluent-plugin", List.of()))),
                null, "us-east-1"));

        assertEquals("ClientException", failure.getErrorCode());
        assertEquals("FireLens configuration type must be fluentbit or fluentd.", failure.getMessage());
        verify(lifecycleManager, never()).create(any(ContainerSpec.class), any());
    }

    @ParameterizedTest
    @CsvSource({"true,500", "false,0", "false,13"})
    void firelensUploadUsesTheSelectedBackoffAndSharedRetryBudget(boolean productionDefaults, long backoffMillis) {
        try (MockedStatic<RetryingTarCopier> copies = mockStatic(RetryingTarCopier.class,
                withSettings().mockMaker(MockMakers.INLINE))) {
            TaskDefinition definition = retryTaskDefinition("fluentbit", false, "");
            if (productionDefaults) {
                manager.startTask(task(), definition, null, "us-east-1");
            } else {
                manager.startTask(task(), definition, null, "us-east-1", backoffMillis);
            }

            copies.verify(() -> RetryingTarCopier.copyArchive(eq(dockerClient), eq("router-id"),
                    eq("/fluent-bit/etc"), eq("firelens-config"), any(byte[].class),
                    eq(6), eq(backoffMillis)));
        }
    }

    @ParameterizedTest
    @CsvSource({"fluentbit,false", "fluentbit,true", "fluentd,false", "fluentd,true"})
    void uploadsConfigFromMemoryWithoutAStreamingPipe(String type, boolean external) {
        manager.startTask(task(), retryTaskDefinition(type, external, "# external config\n"), null, "us-east-1");

        assertInstanceOf(ByteArrayInputStream.class, uploadStream,
                "an already-built archive must not allocate a streaming pipe and writer thread");
    }

    @ParameterizedTest
    @CsvSource({"fluentbit,false,0", "fluentbit,false,73", "fluentbit,false,8192",
            "fluentbit,true,0", "fluentbit,true,73", "fluentbit,true,8192",
            "fluentd,false,0", "fluentd,false,73", "fluentd,false,8192",
            "fluentd,true,0", "fluentd,true,73", "fluentd,true,8192"})
    void retriesConsumedConfigArchiveBeforeStartingContainers(String type, boolean external, int consumedBytes)
            throws Exception {
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(dockerClient, "router-id");
        uploads.failures = 1;
        uploads.consumedBytes = consumedBytes;
        uploads.failure = new RuntimeException(new IOException("Connection reset"));
        String extra = "# café ../not-an-entry\n".repeat(1024);

        EcsTaskHandle handle = manager.startTask(task(), retryTaskDefinition(type, external, extra), null, "us-east-1", 0L);

        assertEquals(List.of("log_router", "app"), handle.getContainerIds().keySet().stream().toList());
        assertEquals(2, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        assertNotSame(uploads.streams.getFirst(), uploads.streams.get(1));
        String path = "fluentd".equals(type) ? "/fluentd/etc" : "/fluent-bit/etc";
        String file = "fluentd".equals(type) ? "fluent.conf" : "fluent-bit.conf";
        verify(uploads.commands.get(1)).withRemotePath(path);
        try (TarArchiveInputStream tar = new TarArchiveInputStream(new ByteArrayInputStream(uploads.completed.getFirst()))) {
            assertConfigEntry(tar, file, expectedRetryConfig(type, external));
            if (external) {
                assertConfigEntry(tar, "external.conf", extra);
            }
            assertNull(tar.getNextEntry());
        }
        InOrder order = inOrder(lifecycleManager, uploads.commands.get(1));
        order.verify(lifecycleManager).create(any(), any());
        order.verify(uploads.commands.get(1)).exec();
        order.verify(lifecycleManager).startCreated(eq("router-id"), any());
        order.verify(lifecycleManager).createAndStartEphemeral(any(), any());
        verify(lifecycleManager, never()).removeIfExists(anyString());
        if (external) {
            verify(s3Service).getObject("firelens-configs", "extra.conf");
        }
    }

    @Test
    void exhaustedConfigUploadCleansUpBeforeAnyContainerStarts() {
        DockerArchiveUploadFixture uploads = failingUploads();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> manager.startTask(
                task(), retryTaskDefinition("fluentbit", true, "# external config\n"), null, "us-east-1", 0L));

        assertSame(uploads.failure, failure.getCause());
        assertEquals(6, uploads.commands.size());
        assertEquals(0, uploads.completed.size());
        verifyFailedLaunchCleanup();
    }

    @ParameterizedTest
    @ValueSource(strings = {"fluentbit", "fluentd"})
    void daemonRejectionDoesNotRetryAndCleansUp(String type) {
        DockerArchiveUploadFixture uploads = failingUploads();
        uploads.failure = new NotFoundException("container missing");

        RuntimeException failure = assertThrows(RuntimeException.class, () -> manager.startTask(
                task(), retryTaskDefinition(type, false, ""), null, "us-east-1"));

        assertSame(uploads.failure, failure.getCause());
        assertEquals(1, uploads.commands.size());
        verifyFailedLaunchCleanup();
    }

    @Test
    void routerStartTransportFailureDoesNotRetryTheUploadOrStart() {
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(dockerClient, "router-id");
        RuntimeException transportFailure = new RuntimeException(new IOException("Connection reset"));
        when(lifecycleManager.startCreated(anyString(), any())).thenThrow(transportFailure);

        RuntimeException failure = assertThrows(RuntimeException.class, () -> manager.startTask(
                task(), retryTaskDefinition("fluentbit", false, ""), null, "us-east-1"));

        assertSame(transportFailure, failure);
        assertEquals(1, uploads.commands.size());
        assertEquals(1, uploads.completed.size());
        verify(lifecycleManager).startCreated(eq("router-id"), any());
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
        verify(lifecycleManager).removeIfExists("router-id");
        verifyFirelensVolumeRemoved();
    }

    @Test
    void socketIdentityIncludesTheCompleteOwnerAndTask() {
        String longNamespace = "firelens-" + "namespace".repeat(20);
        when(config.docker().resourceNamespace()).thenReturn(Optional.of(longNamespace));
        String name = EcsContainerManager.firelensVolumeName(config, task().getTaskArn());
        assertEquals(name, EcsContainerManager.firelensVolumeName(config, task().getTaskArn()));
        assertTrue(name.length() <= 55);
        assertNotEquals(name, EcsContainerManager.firelensVolumeName(config, task().getTaskArn() + "other"));
        when(config.docker().resourceNamespace()).thenReturn(Optional.of(longNamespace + "other"));
        assertNotEquals(name, EcsContainerManager.firelensVolumeName(config, task().getTaskArn()));
        when(config.docker().resourceNamespace()).thenReturn(Optional.of(longNamespace));
        when(config.port()).thenReturn(4567);
        assertNotEquals(name, EcsContainerManager.firelensVolumeName(config, task().getTaskArn()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "long", "multibyte"})
    void invalidSocketMountpointIsCompensatedBeforeCreatingContainers(String mountpoint) {
        String path = switch (mountpoint) {
            case "long" -> "/" + "x".repeat(108);
            case "multibyte" -> "/" + "é".repeat(54);
            default -> mountpoint;
        };
        when(dockerClient.inspectVolumeCmd(anyString()).exec().getMountpoint()).thenReturn(path);
        assertThrows(AwsException.class, () -> manager.startTask(
                task(), retryTaskDefinition("fluentbit", false, ""), null, "us-east-1"));
        verify(lifecycleManager, never()).create(any(), any());
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
        verifyFirelensVolumeRemoved();
    }

    @Test
    void failedPreparationCleanupRetainsTheVolumeForRetry() {
        when(dockerClient.inspectVolumeCmd(anyString()).exec().getMountpoint()).thenReturn(null);
        when(lifecycleManager.removeVolume(anyString())).thenReturn(false, true);
        EcsTaskLaunchException failure = assertThrows(EcsTaskLaunchException.class, () -> manager.startTask(
                task(), retryTaskDefinition("fluentbit", false, ""), null, "us-east-1"));
        assertTrue(failure.getHandle().hasTaskVolumes());
        manager.cleanupStoppedTask(failure.getHandle());
        assertFalse(failure.getHandle().hasTaskVolumes());
    }

    @Test
    void socketVolumeKeepsFullOwnershipLabels() {
        when(config.docker().resourceNamespace()).thenReturn(Optional.of("long-owner-namespace"));
        when(config.port()).thenReturn(4566);
        manager.startTask(task(), retryTaskDefinition("fluentbit", false, ""), null, "us-east-1");
        ArgumentCaptor<Map<String, String>> labels = ArgumentCaptor.captor();
        verify(lifecycleManager).ensureVolume(anyString(), labels.capture());
        assertEquals("long-owner-namespace/4566", labels.getValue().get("io.floci.owner"));
        assertEquals("ecs", labels.getValue().get("io.floci.service"));
        assertTrue(labels.getValue().containsKey("io.floci.ecs.run"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"floci-aws-long-namespace-ecs-firelens-abc123",
            "floci-aws-ecs-firelens-043ba6e4a0fd36cf98016b7b4e985125"})
    void handleCleanupRetriesBothLegacyAndBoundedVolumeNames(String volumeName) {
        EcsTaskHandle handle = new EcsTaskHandle(task().getTaskArn(), Map.of(), Map.of(),
                volumeName, null, "us-east-1");
        when(lifecycleManager.removeVolume(volumeName)).thenReturn(false, true);
        manager.cleanupStoppedTask(handle);
        assertTrue(handle.hasTaskVolumes());
        manager.cleanupStoppedTask(handle);
        assertFalse(handle.hasTaskVolumes());
    }

    @ParameterizedTest
    @CsvSource({"107,true", "108,false"})
    void socketLengthChecksTheCompleteHostPath(int bytes, boolean accepted) {
        String mountpoint = "/" + "x".repeat(bytes - 13);
        when(dockerClient.inspectVolumeCmd(anyString()).exec().getMountpoint()).thenReturn(mountpoint);
        if (accepted) {
            manager.startTask(task(), retryTaskDefinition("fluentbit", false, ""), null, "us-east-1");
        } else {
            assertThrows(AwsException.class, () -> manager.startTask(
                    task(), retryTaskDefinition("fluentbit", false, ""), null, "us-east-1"));
            verify(lifecycleManager, never()).create(any(), any());
            verifyFirelensVolumeRemoved();
        }
    }

    private DockerArchiveUploadFixture failingUploads() {
        DockerArchiveUploadFixture uploads = new DockerArchiveUploadFixture(dockerClient, "router-id");
        uploads.failures = Integer.MAX_VALUE;
        uploads.consumedBytes = 73;
        uploads.failure = new RuntimeException(new IOException("Connection reset"));
        return uploads;
    }

    private void verifyFailedLaunchCleanup() {
        verify(lifecycleManager).create(any(), any());
        verify(lifecycleManager, never()).startCreated(anyString(), any());
        verify(lifecycleManager, never()).createAndStartEphemeral(any(), any());
        verify(lifecycleManager).removeIfExists("router-id");
        verifyFirelensVolumeRemoved();
    }

    private void verifyFirelensVolumeRemoved() {
        ArgumentCaptor<String> volumeName = ArgumentCaptor.forClass(String.class);
        verify(lifecycleManager).ensureVolume(volumeName.capture(), any());
        verify(lifecycleManager).removeVolume(volumeName.getValue());
    }

    private TaskDefinition retryTaskDefinition(String type, boolean external, String extra) {
        ContainerDefinition router = router(type, List.of());
        Map<String, String> options = external
                ? Map.of("enable-ecs-log-metadata", "false", "config-file-type", "s3",
                        "config-file-value", "arn:aws:s3:::firelens-configs/extra.conf")
                : Map.of("enable-ecs-log-metadata", "false");
        router.setFirelensConfiguration(new FirelensConfiguration(type, options));
        ContainerDefinition app = new ContainerDefinition();
        app.setName("app");
        app.setImage("app:latest");
        app.setLogConfiguration(new LogConfiguration("awsfirelens",
                Map.of("fluentd".equals(type) ? "@type" : "Name", "stdout"), null));
        if (external) {
            S3Object object = new S3Object();
            object.setData(extra.getBytes(StandardCharsets.UTF_8));
            when(s3Service.getObject("firelens-configs", "extra.conf")).thenReturn(object);
        }
        TaskDefinition definition = taskDefinition(List.of(app, router));
        definition.setNetworkMode(NetworkMode.host);
        return definition;
    }

    private static String expectedRetryConfig(String type, boolean external) {
        if ("fluentd".equals(type)) {
            return "<source>\n    @type unix\n    path /var/run/fluent.sock\n</source>\n\n"
                    + (external ? "@include /fluentd/etc/external.conf\n\n" : "")
                    + "<match app-firelens**>\n    @type stdout\n</match>\n\n";
        }
        return "[INPUT]\n    Name forward\n    unix_path /var/run/fluent.sock\n    Mem_Buf_Limit 25MB\n\n"
                + (external ? "@INCLUDE /fluent-bit/etc/external.conf\n\n" : "")
                + "[OUTPUT]\n    Name stdout\n    Match app-firelens*\n\n";
    }

    private static void assertConfigEntry(TarArchiveInputStream tar, String name, String content) throws IOException {
        TarArchiveEntry entry = tar.getNextEntry();
        assertEquals(name, entry.getName());
        assertEquals(0100644, entry.getMode());
        assertEquals(content, new String(tar.readAllBytes(), StandardCharsets.UTF_8));
    }

    private static ContainerDefinition router(String type, List<PortMapping> portMappings) {
        ContainerDefinition router = new ContainerDefinition();
        router.setName("log_router");
        router.setImage("amazon/aws-for-fluent-bit:stable");
        router.setFirelensConfiguration(new FirelensConfiguration(type, Map.of()));
        router.setPortMappings(portMappings);
        return router;
    }

    private static TaskDefinition taskDefinition(List<ContainerDefinition> definitions) {
        TaskDefinition taskDef = new TaskDefinition();
        taskDef.setFamily("firelens-family");
        taskDef.setRevision(1);
        taskDef.setNetworkMode(NetworkMode.awsvpc);
        taskDef.setContainerDefinitions(definitions);
        return taskDef;
    }

    private static EcsTask task() {
        EcsTask task = new EcsTask();
        task.setTaskArn("arn:aws:ecs:us-east-1:000000000000:task/test-cluster/abc123");
        task.setClusterArn("arn:aws:ecs:us-east-1:000000000000:cluster/test-cluster");
        return task;
    }

    private static Map<String, String> readFirelensArchive(InputStream archive) {
        LinkedHashMap<String, String> files = new LinkedHashMap<>();
        try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                files.put(entry.getName(), new String(tar.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return files;
    }

    private static String readFluentBitConf(InputStream archive) {
        try (TarArchiveInputStream tar = new TarArchiveInputStream(archive)) {
            tar.getNextEntry();
            // TarArchiveInputStream.read stops at the end of the current entry.
            return new String(tar.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
