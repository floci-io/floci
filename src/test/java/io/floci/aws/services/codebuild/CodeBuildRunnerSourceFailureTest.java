package io.floci.aws.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.docker.ContainerBuilder;
import io.floci.aws.core.common.docker.ContainerDetector;
import io.floci.aws.core.common.docker.ContainerLifecycleManager;
import io.floci.aws.core.common.docker.ContainerLogStreamer;
import io.floci.aws.core.common.docker.ContainerReachableEndpoint;
import io.floci.aws.core.common.docker.ContainerSpec;
import io.floci.aws.core.common.docker.ContainerStorageHelper;
import io.floci.aws.services.codebuild.model.Build;
import io.floci.aws.services.codebuild.model.BuildPhase;
import io.floci.aws.services.codebuild.model.Project;
import io.floci.aws.services.codebuild.model.ProjectEnvironment;
import io.floci.aws.services.codebuild.model.ProjectSource;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Subnet;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
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

    @Test
    void aBuildInAVpcJoinsItsNetworkBeforeTheSourceIsCopiedAndGivesTheAddressBackOnceRemoved() throws Exception {
        Ec2Service ec2 = mock(Ec2Service.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(ec2).runWithSubnetReferencesHeld(eq("us-east-1"), any());
        Subnet subnet = new Subnet();
        subnet.setSubnetId("subnet-1");
        subnet.setVpcId("vpc-1");
        when(ec2.describeSubnets("us-east-1", List.of("subnet-1"), Map.of())).thenReturn(List.of(subnet));
        when(ec2.allocateVpcPrivateIp("us-east-1", "subnet-1")).thenReturn(Optional.of("10.0.1.20"));
        when(ec2.attachContainerToVpc("us-east-1", "vpc-1", "subnet-1", "container", "10.0.1.20"))
                .thenReturn(Optional.of("10.0.1.20"));
        Fixture fixture = fixture(false, ec2);
        // StartBuild copied the project's vpcConfig onto the build; the project has since been updated.
        fixture.build().setVpcConfig(Map.of("vpcId", "vpc-1", "subnets", List.of("subnet-1", "subnet-2"),
                "securityGroupIds", List.of("sg-1")));
        fixture.project().setVpcConfig(Map.of("vpcId", "vpc-9", "subnets", List.of("subnet-9")));
        AtomicBoolean heldDuringCopy = new AtomicBoolean();
        doAnswer(invocation -> {
            heldDuringCopy.set(fixture.runner().hasSubnetDependency("us-east-1", "subnet-1")
                    && fixture.runner().hasVpcDependency("us-east-1", "vpc-1"));
            throw new IllegalStateException("source upload failed");
        }).when(fixture.runner()).copySourceToContainer(eq("container"), any(), anyString());
        when(fixture.docker().inspectContainerCmd("container").exec()).thenThrow(new NotFoundException("gone"));

        fixture.run();

        assertTrue(heldDuringCopy.get(), "the build's address holds its subnet and VPC while it runs");
        // The address is given back right after the container is removed, on the build's thread.
        verify(ec2, timeout(5_000)).releaseVpcPrivateIp("us-east-1", "subnet-1", "10.0.1.20");
        assertFalse(fixture.runner().hasSubnetDependency("us-east-1", "subnet-1"));
    }

    @Test
    void containersAnEarlierRunLeftAreRemovedBeforeABuildStarts() throws Exception {
        Fixture fixture = fixture(false);
        Container leftover = mock(Container.class);
        when(leftover.getId()).thenReturn("leftover");
        when(leftover.getLabels()).thenReturn(Map.of(
                ContainerStorageHelper.SERVICE_LABEL, "codebuild",
                ContainerStorageHelper.OWNER_LABEL, "0",
                CodeBuildRunner.RUN_LABEL, "an-earlier-run"));
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        when(fixture.lifecycle().getDockerClient()).thenReturn(docker);
        when(docker.listContainersCmd().withShowAll(true).withLabelFilter(anyMap()).exec())
                .thenReturn(List.of(leftover));

        fixture.run();

        verify(fixture.lifecycle()).removeIfExistsStrict("leftover", true);
    }

    private Fixture fixture(boolean stopDuringCopy) throws IOException {
        return fixture(stopDuringCopy, null);
    }

    private Fixture fixture(boolean stopDuringCopy, Ec2Service ec2) throws IOException {
        DockerClient docker = mock(DockerClient.class, RETURNS_DEEP_STUBS);
        ContainerBuilder containerBuilder = mock(ContainerBuilder.class);
        ContainerBuilder.Builder specBuilder = mock(ContainerBuilder.Builder.class, RETURNS_SELF);
        when(containerBuilder.newContainer(anyString())).thenReturn(specBuilder);
        when(specBuilder.build()).thenReturn(new ContainerSpec("alpine:latest"));

        ContainerLifecycleManager lifecycle = mock(ContainerLifecycleManager.class);
        when(lifecycle.createAndStart(any())).thenReturn(new ContainerLifecycleManager.ContainerInfo("container", Map.of()));
        CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> { finished.countDown(); return null; })
                .when(lifecycle).stopAndRemove(eq("container"), nullable(Closeable.class));

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

        CodeBuildRunner runner = spy(new CodeBuildRunner(docker, containerBuilder, lifecycle, logStreamer,
                s3, null, null, config, mock(ContainerDetector.class), regionResolver, endpoint, ec2));
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

        return new Fixture(runner, build, project, finished, docker, lifecycle);
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

    private record Fixture(CodeBuildRunner runner, Build build, Project project, CountDownLatch finished,
                           DockerClient docker, ContainerLifecycleManager lifecycle) {
        void run() throws InterruptedException {
            runner.startBuild("us-east-1", build, project, null);
            assertTrue(finished.await(10, TimeUnit.SECONDS), "build did not finish");
        }
    }
}
