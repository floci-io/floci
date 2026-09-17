package io.github.hectorvent.floci.services.ecs.container;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import com.github.dockerjava.api.command.InspectVolumeCmd;
import com.github.dockerjava.api.command.InspectVolumeResponse;
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
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.FirelensConfiguration;
import io.github.hectorvent.floci.services.ecs.model.LogConfiguration;
import io.github.hectorvent.floci.services.ecs.model.NetworkMode;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.services.s3.model.S3Object;
import io.github.hectorvent.floci.services.secretsmanager.SecretsManagerService;
import io.github.hectorvent.floci.services.ssm.SsmService;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class EcsContainerManagerFirelensTest {

    private ContainerBuilder.Builder builder;
    private ContainerLifecycleManager lifecycleManager;
    private S3Service s3Service;
    private ContainerLogStreamer logStreamer;
    private DockerClient dockerClient;
    private CopyArchiveToContainerCmd copyCmd;
    private EcsContainerManager manager;

    @BeforeEach
    void setUp() {
        builder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(new ContainerSpec("image"));
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        when(containerBuilder.newContainer(anyString())).thenReturn(builder);

        lifecycleManager = mock(ContainerLifecycleManager.class);
        when(lifecycleManager.createAndStart(any())).thenReturn(new ContainerInfo("app-id", Map.of()));
        when(lifecycleManager.create(any())).thenReturn("router-id");
        when(lifecycleManager.startCreated(anyString(), any())).thenReturn(new ContainerInfo("router-id", Map.of()));

        dockerClient = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(lifecycleManager.getDockerClient()).thenReturn(dockerClient);
        InspectVolumeCmd inspectVolumeCmd = mock(InspectVolumeCmd.class);
        InspectVolumeResponse volume = mock(InspectVolumeResponse.class);
        when(dockerClient.inspectVolumeCmd(anyString())).thenReturn(inspectVolumeCmd);
        when(inspectVolumeCmd.exec()).thenReturn(volume);
        when(volume.getMountpoint()).thenReturn("/var/lib/docker/volumes/floci-ecs-firelens-abc123/_data");
        copyCmd = mock(CopyArchiveToContainerCmd.class, RETURNS_SELF);
        when(dockerClient.copyArchiveToContainerCmd("router-id")).thenReturn(copyCmd);

        logStreamer = mock(ContainerLogStreamer.class);
        when(logStreamer.generateLogStreamName(anyString())).thenReturn("stream");
        ContainerDetector containerDetector = mock(ContainerDetector.class);
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
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
        order.verify(lifecycleManager).create(any(ContainerSpec.class));
        order.verify(lifecycleManager).startCreated(anyString(), any());
        order.verify(lifecycleManager).createAndStart(any(ContainerSpec.class));

        ArgumentCaptor<LogConfig> logConfig = ArgumentCaptor.forClass(LogConfig.class);
        verify(builder).withLogConfig(logConfig.capture());
        assertEquals(LogConfig.LoggingType.FLUENTD, logConfig.getValue().getType());
        assertEquals("unix:///var/lib/docker/volumes/floci-ecs-firelens-abc123/_data/fluent.sock",
                logConfig.getValue().getConfig().get("fluentd-address"));
        assertEquals("app-firelens-abc123", logConfig.getValue().getConfig().get("tag"));
        assertEquals("true", logConfig.getValue().getConfig().get("fluentd-async"));
        assertTrue(logConfig.getValue().getConfig().get("fluentd-async-connect") == null,
                "fluentd-async-connect was removed by Docker 28");

        verify(logStreamer).attach(eq("router-id"), anyString(), anyString(), anyString(), anyString());
        verify(logStreamer, never()).attach(eq("app-id"), anyString(), anyString(), anyString(), anyString());
        assertTrue(handle.getFirelensVolumeName().contains("firelens"));

        verify(copyCmd).withRemotePath("/fluent-bit/etc");
        ArgumentCaptor<InputStream> configArchive = ArgumentCaptor.forClass(InputStream.class);
        verify(copyCmd).withTarInputStream(configArchive.capture());
        assertTrue(readFluentBitConf(configArchive.getValue()).contains(
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
        order.verify(lifecycleManager).create(any(ContainerSpec.class));
        order.verify(lifecycleManager).startCreated(anyString(), any());
        order.verify(lifecycleManager).createAndStart(any(ContainerSpec.class));

        ArgumentCaptor<LogConfig> logConfig = ArgumentCaptor.forClass(LogConfig.class);
        verify(builder).withLogConfig(logConfig.capture());
        assertEquals(LogConfig.LoggingType.FLUENTD, logConfig.getValue().getType());
        assertEquals("unix:///var/lib/docker/volumes/floci-ecs-firelens-abc123/_data/fluent.sock",
                logConfig.getValue().getConfig().get("fluentd-address"));

        verify(copyCmd).withRemotePath("/fluentd/etc");
        ArgumentCaptor<InputStream> configArchive = ArgumentCaptor.forClass(InputStream.class);
        verify(copyCmd).withTarInputStream(configArchive.capture());
        String conf = readFluentBitConf(configArchive.getValue());
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
        ArgumentCaptor<InputStream> configArchive = ArgumentCaptor.forClass(InputStream.class);
        verify(copyCmd).withTarInputStream(configArchive.capture());
        Map<String, String> files = readFirelensArchive(configArchive.getValue());
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
        ArgumentCaptor<InputStream> configArchive = ArgumentCaptor.forClass(InputStream.class);
        verify(copyCmd).withTarInputStream(configArchive.capture());
        Map<String, String> files = readFirelensArchive(configArchive.getValue());
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
        verify(lifecycleManager, never()).create(any(ContainerSpec.class));
        verify(lifecycleManager, never()).createAndStart(any());
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
