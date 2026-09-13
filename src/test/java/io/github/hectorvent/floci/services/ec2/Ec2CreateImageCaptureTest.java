package io.github.hectorvent.floci.services.ec2;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CommitCmd;
import com.github.dockerjava.api.command.RemoveImageCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerDetector;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.PortAllocator;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.net.VpcNetworkManager;
import io.github.hectorvent.floci.services.ec2.portforward.Ec2PortForwardManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Answers.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * CreateImage used to record only metadata: the new AMI carried a sourceImageId pointing at the
 * ancestor, so launching it started the *base* image and everything provisioned on the source
 * instance was silently discarded. A Packer build against Floci therefore reported success and
 * produced an empty artifact -- the AMI existed, was "available", and contained nothing.
 *
 * <p>These cover the capture itself. That the captured image is what actually gets launched is
 * covered end to end by a real Packer build; see the commit message.
 */
class Ec2CreateImageCaptureTest {

    private static final String CONTAINER_ID = "container-1";

    private static Ec2ContainerManager managerWith(DockerClient dockerClient) {
        return new Ec2ContainerManager(
                mock(ContainerBuilder.class),
                mock(ContainerLifecycleManager.class),
                mock(ContainerLogStreamer.class),
                mock(ContainerDetector.class),
                mock(DockerHostResolver.class),
                dockerClient,
                mock(PortAllocator.class),
                mock(EmulatorConfig.class),
                mock(Ec2MetadataServer.class),
                mock(Ec2PortForwardManager.class),
                mock(RegionResolver.class),
                mock(ContainerNetworkReachability.class),
                mock(VpcNetworkManager.class));
    }

    private static Instance instance(String containerId) {
        Instance instance = new Instance();
        instance.setInstanceId("i-capture");
        instance.setDockerContainerId(containerId);
        return instance;
    }

    @Test
    void commitInstanceCommitsTheContainerToTheRequestedRepositoryAndTag() {
        DockerClient dockerClient = mock(DockerClient.class);
        CommitCmd commit = mock(CommitCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.commitCmd(CONTAINER_ID)).thenReturn(commit);
        when(commit.exec()).thenReturn("sha256:abc123");

        Ec2ContainerManager manager = managerWith(dockerClient);

        String tag = manager.commitInstance(instance(CONTAINER_ID), "floci-ami/ami-123:latest");

        assertEquals("floci-ami/ami-123:latest", tag);
        // Repository and tag must be split, not passed as one string -- docker-java would
        // otherwise produce a repository literally named "floci-ami/ami-123:latest".
        verify(commit).withRepository("floci-ami/ami-123");
        verify(commit).withTag("latest");
    }

    @Test
    void commitInstanceDefaultsTheTagWhenTheReferenceHasNone() {
        DockerClient dockerClient = mock(DockerClient.class);
        CommitCmd commit = mock(CommitCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.commitCmd(CONTAINER_ID)).thenReturn(commit);
        when(commit.exec()).thenReturn("sha256:abc123");

        managerWith(dockerClient).commitInstance(instance(CONTAINER_ID), "floci-ami/ami-123");

        verify(commit).withRepository("floci-ami/ami-123");
        verify(commit).withTag("latest");
    }

    @Test
    void commitInstanceWithoutAContainerCapturesNothing() {
        DockerClient dockerClient = mock(DockerClient.class);

        assertNull(managerWith(dockerClient).commitInstance(instance(null), "floci-ami/ami-1:latest"));

        verify(dockerClient, never()).commitCmd(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aFailedCaptureThrowsRatherThanReportingNothingToCapture() {
        // Reporting a refused commit the same way as "there was nothing to commit" hands back an
        // AMI that is immediately available and boots the ancestor, so a Packer build succeeds
        // and produces the empty artifact this capture exists to prevent. CreateImage turns this
        // into a failed call.
        DockerClient dockerClient = mock(DockerClient.class);
        CommitCmd commit = mock(CommitCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.commitCmd(CONTAINER_ID)).thenReturn(commit);
        when(commit.exec()).thenThrow(new RuntimeException("daemon is unavailable"));

        Ec2ContainerManager.CaptureFailedException failure = assertThrows(
                Ec2ContainerManager.CaptureFailedException.class,
                () -> managerWith(dockerClient)
                        .commitInstance(instance(CONTAINER_ID), "floci-ami/ami-123:latest"));

        assertTrue(failure.getMessage().contains("daemon is unavailable"));
    }

    @Test
    void removeCommittedImageRemovesTheCapture() {
        DockerClient dockerClient = mock(DockerClient.class);
        RemoveImageCmd remove = mock(RemoveImageCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.removeImageCmd("floci-ami/ami-123:latest")).thenReturn(remove);

        assertTrue(managerWith(dockerClient).removeCommittedImage("floci-ami/ami-123:latest"));

        verify(remove).withForce(true);
        verify(remove).exec();
    }

    @Test
    void removingAnAlreadyAbsentCaptureIsNotAnError() {
        // Deregistering twice, or after a docker prune, must not surface as a failure.
        DockerClient dockerClient = mock(DockerClient.class);
        RemoveImageCmd remove = mock(RemoveImageCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.removeImageCmd("floci-ami/ami-gone:latest")).thenReturn(remove);
        when(remove.exec()).thenThrow(new NotFoundException("no such image"));

        assertTrue(managerWith(dockerClient).removeCommittedImage("floci-ami/ami-gone:latest"));
    }

    @Test
    void aRemovalTheDaemonRefusesIsReportedAsNotRemoved() {
        // The caller clears the AMI's reference to the layer on the strength of this answer, and
        // nothing can rediscover the tag afterwards, so a refusal must not read as a removal.
        DockerClient dockerClient = mock(DockerClient.class);
        RemoveImageCmd remove = mock(RemoveImageCmd.class, withSettings().defaultAnswer(RETURNS_SELF));
        when(dockerClient.removeImageCmd("floci-ami/ami-busy:latest")).thenReturn(remove);
        when(remove.exec()).thenThrow(new RuntimeException("conflict: image is in use"));

        assertFalse(managerWith(dockerClient).removeCommittedImage("floci-ami/ami-busy:latest"));
    }

    @Test
    void removeCommittedImageIgnoresAnAmiThatWasNeverCaptured() {
        DockerClient dockerClient = mock(DockerClient.class);

        assertTrue(managerWith(dockerClient).removeCommittedImage(null));

        verify(dockerClient, never()).removeImageCmd(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void committedImageTagIsNamespacedAndUniquePerAmi() {
        assertEquals("floci-ami/ami-123:latest", Ec2Service.committedImageTag("ami-123"));
        assertEquals("floci-ami/ami-456:latest", Ec2Service.committedImageTag("ami-456"));
    }
}
