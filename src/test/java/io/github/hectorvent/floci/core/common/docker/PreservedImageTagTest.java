package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.RemoveImageCmd;
import com.github.dockerjava.api.command.TagImageCmd;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.opentest4j.TestAbortedException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PreservedImageTagTest {
    private DockerClient docker;
    private InspectImageCmd inspect;
    private TagImageCmd tag;
    private RemoveImageCmd remove;

    @BeforeEach
    void setUp() {
        docker = mock(DockerClient.class);
        inspect = mock(InspectImageCmd.class);
        InspectImageResponse image = mock(InspectImageResponse.class);
        tag = mock(TagImageCmd.class);
        remove = mock(RemoveImageCmd.class);
        when(docker.inspectImageCmd("busybox:1.37.0")).thenReturn(inspect);
        when(inspect.exec()).thenReturn(image);
        when(image.getId()).thenReturn("original-image");
        when(docker.tagImageCmd(anyString(), anyString(), anyString())).thenReturn(tag);
        when(docker.removeImageCmd(anyString())).thenReturn(remove);
    }

    @ParameterizedTest
    @ValueSource(strings = {"success", "skip", "pull-failure", "build-failure"})
    void restoresOriginalImageForEveryExit(String exit) {
        RuntimeException failure = "skip".equals(exit)
                ? new TestAbortedException("unsupported fixture")
                : new IllegalStateException(exit);
        if ("success".equals(exit)) {
            try (PreservedImageTag saved = new PreservedImageTag(docker, "busybox", "1.37.0")) {
                verify(docker).inspectImageCmd("busybox:1.37.0");
            }
        } else {
            assertSame(failure, assertThrows(RuntimeException.class, () -> {
                try (PreservedImageTag saved = new PreservedImageTag(docker, "busybox", "1.37.0")) {
                    throw failure;
                }
            }));
        }
        ArgumentCaptor<String> backupTag = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(docker, tag, remove);
        order.verify(docker).tagImageCmd(eq("original-image"), eq("floci/helper-base-backup"), backupTag.capture());
        order.verify(tag).exec();
        String backup = "floci/helper-base-backup:" + backupTag.getValue();
        order.verify(docker).tagImageCmd(backup, "busybox", "1.37.0");
        order.verify(tag).exec();
        order.verify(docker).removeImageCmd(backup);
        order.verify(remove).exec();
    }

    @Test
    void removesTagThatWasInitiallyAbsent() {
        when(inspect.exec()).thenThrow(new NotFoundException("absent"));
        try (PreservedImageTag saved = new PreservedImageTag(docker, "busybox", "1.37.0")) {
            verify(docker).inspectImageCmd("busybox:1.37.0");
        }
        verify(docker).removeImageCmd("busybox:1.37.0");
        verify(remove).exec();
        verify(docker, never()).tagImageCmd(anyString(), anyString(), anyString());
    }

    @Test
    void absentTagAfterFailedPullIsAlreadyClean() {
        when(inspect.exec()).thenThrow(new NotFoundException("absent"));
        doThrow(new NotFoundException("pull never created tag")).when(remove).exec();
        assertDoesNotThrow(() -> {
            try (PreservedImageTag saved = new PreservedImageTag(docker, "busybox", "1.37.0")) {
                verify(docker).inspectImageCmd("busybox:1.37.0");
            }
        });
    }

    @Test
    void snapshotFailureDoesNotMutateTags() {
        IllegalStateException failure = new IllegalStateException("inspect failed");
        when(inspect.exec()).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> new PreservedImageTag(docker, "busybox", "1.37.0")));
        verify(docker, never()).tagImageCmd(anyString(), anyString(), anyString());
        verify(docker, never()).removeImageCmd(anyString());
    }

    @Test
    void restorationFailureRetainsBackupAndIsSuppressedByPrimaryFailure() {
        IllegalStateException restore = new IllegalStateException("restore failed");
        IllegalStateException primary = new IllegalStateException("build failed");
        doNothing().doThrow(restore).when(tag).exec();
        assertSame(primary, assertThrows(IllegalStateException.class, () -> {
            try (PreservedImageTag saved = new PreservedImageTag(docker, "busybox", "1.37.0")) {
                throw primary;
            }
        }));
        assertArrayEquals(new Throwable[]{restore}, primary.getSuppressed());
        verify(docker, never()).removeImageCmd(anyString());
    }
}
