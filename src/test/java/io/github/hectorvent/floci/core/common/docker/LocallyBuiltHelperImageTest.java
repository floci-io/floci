package io.github.hectorvent.floci.core.common.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.BuildImageCmd;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the decisions this helper makes before it ever touches a Dockerfile: that a caller pointed
 * at something other than Floci's own default is left to pull that image itself, that an image
 * already in the daemon is not rebuilt, and that a build which produces no image id fails instead
 * of reporting success. All are branches the callers depend on and none needs a real daemon, so
 * they are pinned here rather than only through the EC2 security-group path, which uses the default
 * image and is skipped without Docker.
 */
class LocallyBuiltHelperImageTest {

    private static final String DEFAULT_IMAGE = "floci/network-helper:local";
    private static final String DOCKERFILE = "/docker/network-helper.Dockerfile";

    private DockerClient dockerClient;
    private ContainerBuilder containerBuilder;

    @BeforeEach
    void setUp() {
        dockerClient = mock(DockerClient.class);
        containerBuilder = mock(ContainerBuilder.class);
    }

    @Test
    void buildsNothingWhenTheCallerIsPointedAtSomeOtherImage() {
        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                "ghcr.io/someone/their-helper:2", DEFAULT_IMAGE, DOCKERFILE);

        // Not even an inspect: that image is the caller's own to make pullable.
        verify(dockerClient, never()).inspectImageCmd(any());
        verify(dockerClient, never()).buildImageCmd(any(InputStream.class));
        verify(containerBuilder, never()).resolveImage(any());
    }

    @Test
    void buildsNothingWhenTheImageIsAlreadyInTheDaemon() {
        stubResolvedImage();
        InspectImageCmd inspectCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(DEFAULT_IMAGE)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(mock(InspectImageResponse.class));

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                DEFAULT_IMAGE, DEFAULT_IMAGE, DOCKERFILE);

        verify(dockerClient, never()).buildImageCmd(any(InputStream.class));
    }

    @Test
    void buildsFromTheDockerfileWhenTheImageIsMissing() {
        stubMissingImage();
        stubBuildReturning("sha256:built");

        LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                DEFAULT_IMAGE, DEFAULT_IMAGE, DOCKERFILE);

        verify(dockerClient).buildImageCmd(any(InputStream.class));
    }

    @Test
    void failsWhenTheBuildProducesNoImageId() {
        stubMissingImage();
        stubBuildReturning(null);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                        DEFAULT_IMAGE, DEFAULT_IMAGE, DOCKERFILE));

        assertTrue(thrown.getMessage().contains(DEFAULT_IMAGE), thrown.getMessage());
    }

    @Test
    void failsLoudlyWhenTheDockerfileIsNotOnTheClasspath() {
        stubMissingImage();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> LocallyBuiltHelperImage.ensureBuilt(dockerClient, containerBuilder,
                        DEFAULT_IMAGE, DEFAULT_IMAGE, "/docker/does-not-exist.Dockerfile"));

        assertTrue(thrown.getMessage().contains("/docker/does-not-exist.Dockerfile"), thrown.getMessage());
        verify(dockerClient, never()).buildImageCmd(any(InputStream.class));
    }

    private void stubResolvedImage() {
        when(containerBuilder.resolveImage(DEFAULT_IMAGE)).thenReturn(DEFAULT_IMAGE);
    }

    private void stubMissingImage() {
        stubResolvedImage();
        InspectImageCmd inspectCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(DEFAULT_IMAGE)).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new NotFoundException("no such image"));
    }

    /**
     * Returns a stub callback rather than the one the helper passes in: awaiting the real
     * {@link BuildImageResultCallback} would block, since no daemon ever feeds it events.
     */
    private void stubBuildReturning(String imageId) {
        BuildImageCmd buildCmd = mock(BuildImageCmd.class, RETURNS_SELF);
        when(dockerClient.buildImageCmd(any(InputStream.class))).thenReturn(buildCmd);
        BuildImageResultCallback result = mock(BuildImageResultCallback.class);
        when(result.awaitImageId()).thenReturn(imageId);
        when(buildCmd.exec(any())).thenReturn(result);
    }
}
