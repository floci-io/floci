package io.floci.aws.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.services.codebuild.model.Build;
import io.floci.aws.services.codebuild.model.BuildPhase;
import io.floci.aws.services.codebuild.model.Project;
import io.floci.aws.services.codebuild.model.ProjectEnvironment;
import io.floci.aws.services.codebuild.model.ProjectSource;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class CodeBuildRunnerSourceFailureTest {

    @Test
    void stoppingDuringSourceUploadKeepsStoppedStatus() throws Exception {
        Fixture fixture = fixture(true);

        fixture.run();

        assertEquals("STOPPED", fixture.build().getBuildStatus());
        assertTrue(fixture.build().getBuildComplete());
    }

    @Test
    void sourceUploadFailureIsRecordedOnDownloadSource() throws Exception {
        Fixture fixture = fixture(false);

        fixture.run();

        assertEquals("FAULT", fixture.build().getBuildStatus());
        BuildPhase sourcePhase = fixture.build().getPhases().stream()
                .filter(phase -> "DOWNLOAD_SOURCE".equals(phase.getPhaseType()))
                .findFirst().orElseThrow();
        assertEquals("FAILED", sourcePhase.getPhaseStatus());
        assertEquals("COMMAND_EXECUTION_ERROR", sourcePhase.getContexts().getFirst().get("statusCode"));
        assertEquals("source upload failed", sourcePhase.getContexts().getFirst().get("message"));
    }

    private Fixture fixture(boolean stopDuringCopy) throws IOException {
        DockerClient docker = mock(DockerClient.class);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        when(specBuilder.build()).thenReturn(new ContainerSpec("alpine:latest"));

        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("container", Map.of()));
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> { finished.countDown(); return null; })
                .when(lifecycle).stopAndRemoveStrict(eq("container"), nullable(Closeable.class));

        ContainerLogStreamer logStreamer = mock(ContainerLogStreamer.class);
        when(logStreamer.attach(eq("container"), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(mock(Closeable.class));

        S3Service s3 = mock(S3Service.class);
        S3Object source = mock(S3Object.class);
        when(source.getData()).thenReturn(sourceArchive());
        when(s3.getObject("source-bucket", "source.zip")).thenReturn(source);

        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().codebuild().dockerNetwork()).thenReturn(Optional.empty());
        RegionResolver regionResolver = mock(RegionResolver.class);
        when(regionResolver.getAccountId()).thenReturn("000000000000");
        ContainerReachableEndpoint endpoint = mock(ContainerReachableEndpoint.class);
        when(endpoint.port()).thenReturn(4566);

        CodeBuildRoleCredentials roleCredentials = mock(CodeBuildRoleCredentials.class);
        when(roleCredentials.provision(anyString(), anyString(), nullable(String.class), anyString(), any()))
                .thenReturn(new CodeBuildRoleCredentials.BuildCredentials(Map.of(), null));
        CodeBuildRunner runner = spy(new CodeBuildRunner(docker, containerBuilder, lifecycle, logStreamer,
                s3, null, null, config, mock(ContainerDetector.class), regionResolver, endpoint, roleCredentials));
        doReturn(CodeBuildRunner.PhaseResult.ofSuccess()).when(runner)
                .runPhase(anyString(), anyString(), anyList(), anyList(), anyInt(), any(AtomicBoolean.class));

        Build build = new Build();
        build.setId("project:build");
        build.setArn("arn:aws:codebuild:us-east-1:000000000000:build/project:build");
        build.setBuildNumber(1L);
        build.setPhases(new ArrayList<>());
        doAnswer(invocation -> {
            if (stopDuringCopy) runner.stopBuild(build.getArn());
            throw new IllegalStateException("source upload failed");
        }).when(runner).copySourceToContainer(eq("container"), any(), anyString());

        Project project = new Project();
        project.setName("project");
        ProjectSource projectSource = new ProjectSource();
        projectSource.setType("S3");
        projectSource.setLocation("source-bucket/source.zip");
        project.setSource(projectSource);
        ProjectEnvironment environment = new ProjectEnvironment();
        environment.setImage("alpine:latest");
        project.setEnvironment(environment);

        return new Fixture(runner, build, project, finished);
    }

    private byte[] sourceArchive() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("buildspec.yml"));
            zip.write("version: 0.2\n".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bytes.toByteArray();
    }

    private record Fixture(CodeBuildRunner runner, Build build, Project project, CountDownLatch finished) {
        void run() throws InterruptedException {
            runner.startBuild("us-east-1", build, project, null);
            assertTrue(finished.await(10, TimeUnit.SECONDS), "build did not finish");
        }
    }
}
