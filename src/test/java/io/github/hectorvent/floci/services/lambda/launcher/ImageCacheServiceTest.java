package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.InternalServerErrorException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.UnauthorizedException;
import com.github.dockerjava.api.model.Info;
import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageCacheServiceTest {

    private static final String IMAGE = "public.ecr.aws/docker/library/alpine:latest";

    @Test
    void pullsImageWhenInspectionReportsNotFound() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        PullImageCmd pullImage = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        when(inspectImage.exec()).thenThrow(new NotFoundException("image not found"))
                .thenReturn(new InspectImageResponse()
                        .withId("sha256:native")
                        .withOs("linux")
                        .withArch("amd64"));
        when(dockerClient.pullImageCmd(IMAGE)).thenReturn(pullImage);
        when(pullImage.withAuthConfig(any())).thenReturn(pullImage);
        when(pullImage.exec(any(PullImageResultCallback.class))).thenReturn(callback);

        newService(dockerClient).ensureImageExists(IMAGE);

        verify(pullImage).exec(any(PullImageResultCallback.class));
        verify(pullImage, never()).withPlatform(nullable(String.class));
        verify(callback).awaitCompletion(5, TimeUnit.MINUTES);
    }

    @Test
    void propagatesImageInspectionFailureWithoutPulling() {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        DockerClientException failure = new DockerClientException("daemon unavailable");
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        when(inspectImage.exec()).thenThrow(failure);

        DockerClientException thrown = assertThrows(DockerClientException.class,
                () -> newService(dockerClient).ensureImageExists(IMAGE));

        assertSame(failure, thrown);
        verify(dockerClient, never()).pullImageCmd(IMAGE);
    }

    @Test
    void pullsRequestedPlatformWhenLocalImageArchitectureDoesNotMatch() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        PullImageCmd pullImage = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        when(inspectImage.exec()).thenReturn(
                new InspectImageResponse().withOs("linux").withArch("amd64"),
                new InspectImageResponse().withId("sha256:arm64").withOs("linux").withArch("arm64"));
        when(dockerClient.pullImageCmd(IMAGE)).thenReturn(pullImage);
        when(pullImage.withAuthConfig(any())).thenReturn(pullImage);
        when(pullImage.withPlatform("linux/arm64")).thenReturn(pullImage);
        when(pullImage.exec(any(PullImageResultCallback.class))).thenReturn(callback);

        String resolvedImage = newService(dockerClient)
                .ensureImageExists(IMAGE, "linux/arm64");

        assertEquals("sha256:arm64", resolvedImage);
        verify(pullImage).withPlatform("linux/arm64");
        verify(callback).awaitCompletion(5, TimeUnit.MINUTES);
    }

    @Test
    void skipsPullWhenLocalImageMatchesRequestedPlatform() {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        when(inspectImage.exec()).thenReturn(new InspectImageResponse()
                .withId("sha256:arm64")
                .withOs("linux")
                .withArch("arm64"));

        String resolvedImage = newService(dockerClient)
                .ensureImageExists(IMAGE, "linux/arm64");

        assertEquals("sha256:arm64", resolvedImage);
        verify(dockerClient, never()).pullImageCmd(IMAGE);
    }

    @Test
    void keepsDifferentPlatformsAsSeparateCacheRequests() {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        PullImageCmd pullImage = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        when(inspectImage.exec()).thenReturn(
                new InspectImageResponse().withOs("linux").withArch("amd64"),
                new InspectImageResponse().withId("sha256:arm64").withOs("linux").withArch("arm64"),
                new InspectImageResponse().withOs("linux").withArch("arm64"),
                new InspectImageResponse().withId("sha256:amd64").withOs("linux").withArch("amd64"));
        when(dockerClient.pullImageCmd(IMAGE)).thenReturn(pullImage);
        when(pullImage.withAuthConfig(any())).thenReturn(pullImage);
        when(pullImage.withPlatform("linux/arm64")).thenReturn(pullImage);
        when(pullImage.withPlatform("linux/amd64")).thenReturn(pullImage);
        when(pullImage.exec(any(PullImageResultCallback.class))).thenReturn(callback);

        ImageCacheService service = newService(dockerClient);
        String armImage = service.ensureImageExists(IMAGE, "linux/arm64");
        String amdImage = service.ensureImageExists(IMAGE, "linux/amd64");

        assertEquals("sha256:arm64", armImage);
        assertEquals("sha256:amd64", amdImage);
        verify(pullImage).withPlatform("linux/arm64");
        verify(pullImage).withPlatform("linux/amd64");
    }

    @Test
    void restoresDaemonDefaultImageAfterPlatformSpecificPull() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        PullImageCmd pullImage = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        when(inspectImage.exec()).thenThrow(new NotFoundException("image not found"))
                .thenReturn(new InspectImageResponse()
                        .withId("sha256:arm64")
                        .withOs("linux")
                        .withArch("arm64"),
                        new InspectImageResponse()
                                .withId("sha256:arm64")
                                .withOs("linux")
                                .withArch("arm64"),
                        new InspectImageResponse()
                                .withId("sha256:amd64")
                                .withOs("linux")
                                .withArch("amd64"));
        when(dockerClient.pullImageCmd(IMAGE)).thenReturn(pullImage);
        when(pullImage.withAuthConfig(any())).thenReturn(pullImage);
        when(pullImage.withPlatform("linux/arm64")).thenReturn(pullImage);
        when(pullImage.exec(any(PullImageResultCallback.class))).thenReturn(callback);

        ImageCacheService service = newService(dockerClient);
        service.ensureImageExists(IMAGE, "linux/arm64");
        service.ensureImageExists(IMAGE);

        verify(pullImage, times(2)).exec(any(PullImageResultCallback.class));
        verify(pullImage).withPlatform("linux/arm64");
    }

    @Test
    void repullsPlatformImageWhenCachedImageWasRemoved() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        InspectImageCmd inspectCachedImage = mock(InspectImageCmd.class);
        PullImageCmd pullImage = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        when(dockerClient.inspectImageCmd("sha256:arm64-old")).thenReturn(inspectCachedImage);
        when(inspectImage.exec()).thenReturn(
                new InspectImageResponse()
                        .withId("sha256:arm64-old")
                        .withOs("linux")
                        .withArch("arm64"),
                new InspectImageResponse().withOs("linux").withArch("amd64"),
                new InspectImageResponse()
                        .withId("sha256:arm64-new")
                        .withOs("linux")
                        .withArch("arm64"));
        when(inspectCachedImage.exec()).thenThrow(new NotFoundException("image was removed"));
        when(dockerClient.pullImageCmd(IMAGE)).thenReturn(pullImage);
        when(pullImage.withAuthConfig(any())).thenReturn(pullImage);
        when(pullImage.withPlatform("linux/arm64")).thenReturn(pullImage);
        when(pullImage.exec(any(PullImageResultCallback.class))).thenReturn(callback);

        ImageCacheService service = newService(dockerClient);
        assertEquals("sha256:arm64-old", service.ensureImageExists(IMAGE, "linux/arm64"));

        assertEquals("sha256:arm64-new", service.ensureImageExists(IMAGE, "linux/arm64"));
        verify(pullImage).exec(any(PullImageResultCallback.class));
    }

    @Test
    void succeedsOnFirstAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageCacheService.runWithRetry(IMAGE, 3, 1L, calls::incrementAndGet);
        assertEquals(1, calls.get());
    }

    @Test
    void retriesOnTransient500AndSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
            int attempt = calls.incrementAndGet();
            if (attempt < 3) {
                throw new InternalServerErrorException(
                        "Status 500: {\"message\":\"toomanyrequests: Rate exceeded\"}");
            }
        });
        assertEquals(3, calls.get());
    }

    @Test
    void exhaustsAttemptsAndRethrowsLast500() {
        AtomicInteger calls = new AtomicInteger();
        InternalServerErrorException ex = assertThrows(InternalServerErrorException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new InternalServerErrorException("backend unavailable");
                }));
        assertEquals(3, calls.get());
        assertTrue(ex.getMessage().contains("backend unavailable"));
    }

    @Test
    void doesNotRetryOnNotFound() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(NotFoundException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new NotFoundException("manifest unknown");
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void doesNotRetryOnUnauthorized() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(UnauthorizedException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new UnauthorizedException("denied");
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void doesNotRetryOnGenericDockerException() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(DockerException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new DockerException("connection refused", -1);
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void retriesOnPullWrapperDockerClientExceptionAndSucceeds() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
            if (calls.incrementAndGet() < 2) {
                throw new DockerClientException(
                        "Could not pull image: toomanyrequests: Rate exceeded");
            }
        });
        assertEquals(2, calls.get());
    }

    @Test
    void doesNotRetryOnNonPullDockerClientException() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(DockerClientException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new DockerClientException("container start failed: exit 137");
                }));
        assertEquals(1, calls.get());
    }

    @Test
    void propagatesInterrupted() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(InterruptedException.class,
                () -> ImageCacheService.runWithRetry(IMAGE, 3, 1L, () -> {
                    calls.incrementAndGet();
                    throw new InterruptedException("interrupted mid-pull");
                }));
        assertEquals(1, calls.get());
    }

    private static ImageCacheService newService(DockerClient dockerClient) {
        InfoCmd infoCmd = mock(InfoCmd.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
        when(infoCmd.exec()).thenReturn(new Info().withOsType("linux").withArchitecture("amd64"));
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.DockerConfig dockerConfig = mock(EmulatorConfig.DockerConfig.class);
        when(config.docker()).thenReturn(dockerConfig);
        when(dockerConfig.registryCredentials()).thenReturn(List.of());
        return new ImageCacheService(dockerClient, config);
    }
}
