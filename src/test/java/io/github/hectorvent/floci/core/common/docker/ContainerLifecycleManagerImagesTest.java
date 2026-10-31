package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CommitCmd;
import com.github.dockerjava.api.command.RemoveImageCmd;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A container kept as an image, and an image removed: gone counts as removed, a refusal does not. */
class ContainerLifecycleManagerImagesTest {

    private final DockerClient docker = mock(DockerClient.class);
    private final ContainerLifecycleManager manager = new ContainerLifecycleManager(docker, null, null, null);

    @Test
    void aCommitNamesTheImageAndSetsItsUserAndLabels() {
        CommitCmd commit = mock(CommitCmd.class, RETURNS_SELF);
        when(commit.exec()).thenReturn("sha256:kept");
        when(docker.commitCmd("container-1")).thenReturn(commit);

        assertEquals("sha256:kept", manager.commit("container-1", "floci-aws-emr-bootstrap", "j-1", "hadoop",
                Map.of("io.floci.component", "emr-bootstrap")));

        verify(commit).withRepository("floci-aws-emr-bootstrap");
        verify(commit).withTag("j-1");
        verify(commit).withUser("hadoop");
        verify(commit).withLabels(Map.of("io.floci.component", "emr-bootstrap"));
    }

    @Test
    void anImageIsRemovedByForceAndOneAlreadyGoneCountsAsRemoved() {
        RemoveImageCmd removed = mock(RemoveImageCmd.class, RETURNS_SELF);
        when(docker.removeImageCmd("kept:1")).thenReturn(removed);
        RemoveImageCmd missing = mock(RemoveImageCmd.class, RETURNS_SELF);
        when(missing.exec()).thenThrow(new NotFoundException("No such image"));
        when(docker.removeImageCmd("gone:1")).thenReturn(missing);

        assertTrue(manager.removeImage("kept:1"));
        assertTrue(manager.removeImage("gone:1"));
        verify(removed).withForce(true);
    }

    @Test
    void anImageTheDaemonRefusesToRemoveIsReported() {
        RemoveImageCmd refused = mock(RemoveImageCmd.class, RETURNS_SELF);
        when(refused.exec()).thenThrow(new ConflictException("image is being used by running container"));
        when(docker.removeImageCmd("in-use:1")).thenReturn(refused);

        assertFalse(manager.removeImage("in-use:1"));
    }
}
