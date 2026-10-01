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
import com.github.dockerjava.api.model.PullResponseItem;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.config.EmulatorConfig.EcsServiceConfig.ImagePullBehavior;
import io.github.hectorvent.floci.services.lambda.launcher.ImageCacheService.LaunchImage;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageCacheServiceTest {

    private static final String IMAGE = "public.ecr.aws/docker/library/alpine:latest";
    private static final String REGISTRY_REPO = "registry.example:5000/app";
    private static final String REGISTRY_IMAGE = REGISTRY_REPO + ":latest";
    private static final String LOCAL_IMAGE = "app-local:latest";

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
    void acceptsThePulledImageWhenInspectionReportsTheHostVariant() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        PullImageCmd pullImage = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        // Docker 29's containerd image store answers for the index, so with the host's variant
        // already local it keeps reporting that architecture after the foreign pull.
        when(inspectImage.exec()).thenReturn(
                new InspectImageResponse().withOs("linux").withArch("arm64"),
                new InspectImageResponse().withId("sha256:index").withOs("linux").withArch("arm64"));
        when(dockerClient.pullImageCmd(IMAGE)).thenReturn(pullImage);
        when(pullImage.withAuthConfig(any())).thenReturn(pullImage);
        when(pullImage.withPlatform("linux/amd64")).thenReturn(pullImage);
        when(pullImage.exec(any(PullImageResultCallback.class))).thenReturn(callback);

        String resolvedImage = newService(dockerClient)
                .ensureImageExists(IMAGE, "linux/amd64");

        assertEquals("sha256:index", resolvedImage);
        verify(pullImage).withPlatform("linux/amd64");
    }

    @Test
    void acceptsThePulledImageWhenInspectionReportsNoPlatform() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        PullImageCmd pullImage = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        // The same store with no other variant local: the index it pulled describes no platform
        // at all.
        when(inspectImage.exec()).thenThrow(new NotFoundException("image not found"))
                .thenReturn(new InspectImageResponse().withId("sha256:index").withOs("").withArch(""));
        when(dockerClient.pullImageCmd(IMAGE)).thenReturn(pullImage);
        when(pullImage.withAuthConfig(any())).thenReturn(pullImage);
        when(pullImage.withPlatform("linux/amd64")).thenReturn(pullImage);
        when(pullImage.exec(any(PullImageResultCallback.class))).thenReturn(callback);

        String resolvedImage = newService(dockerClient)
                .ensureImageExists(IMAGE, "linux/amd64");

        assertEquals("sha256:index", resolvedImage);
        verify(pullImage).withPlatform("linux/amd64");
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
    void repullsDefaultImageWhenCachedImageWasRemoved() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        InspectImageCmd inspectImage = mock(InspectImageCmd.class);
        InspectImageCmd inspectCachedImage = mock(InspectImageCmd.class);
        PullImageCmd pullImage = mock(PullImageCmd.class);
        PullImageResultCallback callback = mock(PullImageResultCallback.class);
        when(dockerClient.inspectImageCmd(IMAGE)).thenReturn(inspectImage);
        when(dockerClient.inspectImageCmd("sha256:default-old")).thenReturn(inspectCachedImage);
        when(inspectImage.exec())
                .thenReturn(new InspectImageResponse()
                        .withId("sha256:default-old")
                        .withOs("linux")
                        .withArch("amd64"))
                .thenThrow(new NotFoundException("image not found locally"))
                .thenReturn(new InspectImageResponse()
                        .withId("sha256:default-new")
                        .withOs("linux")
                        .withArch("amd64"));
        when(inspectCachedImage.exec()).thenThrow(new NotFoundException("image was removed"));
        when(dockerClient.pullImageCmd(IMAGE)).thenReturn(pullImage);
        when(pullImage.withAuthConfig(any())).thenReturn(pullImage);
        when(pullImage.exec(any(PullImageResultCallback.class))).thenReturn(callback);

        ImageCacheService service = newService(dockerClient);
        assertEquals("sha256:default-old", service.ensureImageExists(IMAGE));

        assertEquals("sha256:default-new", service.ensureImageExists(IMAGE));
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

    @Test
    void defaultPullBehaviourRunsTheImageAMovedTagNamesNow() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.registry.put(REGISTRY_IMAGE, image("sha256:old", REGISTRY_REPO + "@sha256:old-manifest"));
        ImageCacheService service = newService(daemon.client);
        assertEquals("sha256:old", service.ensureImageExists(REGISTRY_IMAGE));

        daemon.registry.put(REGISTRY_IMAGE, image("sha256:new", REGISTRY_REPO + "@sha256:new-manifest"));

        assertEquals(new LaunchImage("sha256:new", "sha256:new-manifest"),
                service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.DEFAULT));
        assertEquals("sha256:new", service.ensureImageExists(REGISTRY_IMAGE));
        assertEquals(2, daemon.pulls.get());
    }

    @Test
    void defaultPullBehaviourRunsTheCachedImageWhenThePullFails() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.store(LOCAL_IMAGE, image("sha256:built"));
        ImageCacheService service = newService(daemon.client);

        assertEquals(new LaunchImage("sha256:built", null),
                service.resolveForLaunch(LOCAL_IMAGE, ImagePullBehavior.DEFAULT));
        assertEquals("sha256:built", service.ensureImageExists(LOCAL_IMAGE));
        assertEquals(1, daemon.pulls.get());
    }

    @Test
    void defaultPullBehaviourFailsWhenThePullFailsAndNothingIsCached() {
        FakeDaemon daemon = new FakeDaemon();

        assertThrows(NotFoundException.class,
                () -> newService(daemon.client).resolveForLaunch(LOCAL_IMAGE, ImagePullBehavior.DEFAULT));
    }

    @Test
    void alwaysPullBehaviourFailsWhenThePullFailsEvenWithACachedImage() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.store(LOCAL_IMAGE, image("sha256:built"));

        assertThrows(NotFoundException.class,
                () -> newService(daemon.client).resolveForLaunch(LOCAL_IMAGE, ImagePullBehavior.ALWAYS));
    }

    @Test
    void preferCachedPullBehaviourFollowsTheLocalTagWithoutPulling() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.store(REGISTRY_IMAGE, image("sha256:old", REGISTRY_REPO + "@sha256:old-manifest"));
        ImageCacheService service = newService(daemon.client);
        assertEquals("sha256:old", service.ensureImageExists(REGISTRY_IMAGE));

        daemon.store(REGISTRY_IMAGE, image("sha256:new", REGISTRY_REPO + "@sha256:new-manifest"));

        assertEquals(new LaunchImage("sha256:new", "sha256:new-manifest"),
                service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.PREFER_CACHED));
        assertEquals("sha256:new", service.ensureImageExists(REGISTRY_IMAGE));
        assertEquals(0, daemon.pulls.get());
    }

    @Test
    void preferCachedPullBehaviourPullsWhenNothingIsCached() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.registry.put(REGISTRY_IMAGE, image("sha256:new", REGISTRY_REPO + "@sha256:new-manifest"));

        assertEquals(new LaunchImage("sha256:new", "sha256:new-manifest"),
                newService(daemon.client).resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.PREFER_CACHED));
        assertEquals(1, daemon.pulls.get());
    }

    @Test
    void oncePullBehaviourPullsOnlyTheFirstLaunch() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.store(REGISTRY_IMAGE, image("sha256:stale"));
        daemon.registry.put(REGISTRY_IMAGE, image("sha256:old", REGISTRY_REPO + "@sha256:old-manifest"));
        ImageCacheService service = newService(daemon.client);

        service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.ONCE);
        daemon.registry.put(REGISTRY_IMAGE, image("sha256:new", REGISTRY_REPO + "@sha256:new-manifest"));

        assertEquals(new LaunchImage("sha256:old", "sha256:old-manifest"),
                service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.ONCE));
        assertEquals("sha256:old", service.ensureImageExists(REGISTRY_IMAGE));
        assertEquals(1, daemon.pulls.get());
    }

    @Test
    void aLaunchKeepsTheImageItResolvedWhenAnOverlappingLaunchMovesTheTag() {
        String oldId = "sha256:" + "a".repeat(64);
        String newId = "sha256:" + "b".repeat(64);
        FakeDaemon daemon = new FakeDaemon();
        daemon.registry.put(REGISTRY_IMAGE, image(oldId, REGISTRY_REPO + "@sha256:old-manifest"));
        ImageCacheService service = newService(daemon.client);

        LaunchImage first = service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.DEFAULT);
        daemon.registry.put(REGISTRY_IMAGE, image(newId, REGISTRY_REPO + "@sha256:new-manifest"));
        LaunchImage second = service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.DEFAULT);

        assertEquals(new LaunchImage(oldId, "sha256:old-manifest"), first);
        assertEquals(new LaunchImage(newId, "sha256:new-manifest"), second);
        assertEquals(oldId, service.ensureImageExists(first.imageId()),
                "the first launch's container is created from the image its own pull resolved to");
        assertEquals(newId, service.ensureImageExists(second.imageId()));
        assertEquals(2, daemon.pulls.get());
    }

    @Test
    void anImageIdThatIsGoneFailsWithoutAPull() {
        FakeDaemon daemon = new FakeDaemon();

        assertThrows(DockerClientException.class,
                () -> newService(daemon.client).ensureImageExists("sha256:" + "c".repeat(64)));
        assertEquals(0, daemon.pulls.get());
    }

    @Test
    void anImageIdLaunchesWithoutAPullUnderEveryBehaviour() {
        String imageId = "sha256:" + "d".repeat(64);
        FakeDaemon daemon = new FakeDaemon();
        daemon.store(LOCAL_IMAGE, image(imageId));
        ImageCacheService service = newService(daemon.client);

        for (ImagePullBehavior behavior : ImagePullBehavior.values()) {
            assertEquals(new LaunchImage(imageId, null), service.resolveForLaunch(imageId, behavior));
        }
        assertEquals(0, daemon.pulls.get());
    }

    @Test
    void anImageIdThatIsGoneFailsALaunchWithoutAPull() {
        FakeDaemon daemon = new FakeDaemon();

        assertThrows(DockerClientException.class, () -> newService(daemon.client)
                .resolveForLaunch("sha256:" + "e".repeat(64), ImagePullBehavior.ALWAYS));
        assertEquals(0, daemon.pulls.get());
    }

    @Test
    void aLaunchReportsTheDigestItsPullResolvedTheTagTo() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.registry.put(REGISTRY_IMAGE, image("sha256:config",
                REGISTRY_REPO + "@sha256:platform-manifest", REGISTRY_REPO + "@sha256:index"));
        daemon.registryDigests.put(REGISTRY_IMAGE, "sha256:index");

        assertEquals(new LaunchImage("sha256:config", "sha256:index"),
                newService(daemon.client).resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.DEFAULT));
    }

    @Test
    void aCachedLaunchReportsNoDigestWhenAnOutsidePullMovedTheTagToAnotherManifestOfTheSameImage() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.registry.put(REGISTRY_IMAGE, image("sha256:config", REGISTRY_REPO + "@sha256:first-manifest"));
        daemon.registryDigests.put(REGISTRY_IMAGE, "sha256:first-manifest");
        ImageCacheService service = newService(daemon.client);
        service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.DEFAULT);

        // A docker pull outside Floci moves the tag to a second manifest of the same image config,
        // which the classic image store keeps under the same image id.
        daemon.store(REGISTRY_IMAGE, image("sha256:config",
                REGISTRY_REPO + "@sha256:first-manifest", REGISTRY_REPO + "@sha256:second-manifest"));

        assertEquals(new LaunchImage("sha256:config", null),
                service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.PREFER_CACHED));
    }

    @Test
    void aCachedLaunchReportsTheDigestTheContainerdStoreNamesTheImageBy() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.store(REGISTRY_IMAGE, image("sha256:index",
                REGISTRY_REPO + "@sha256:platform-manifest", REGISTRY_REPO + "@sha256:index"));

        assertEquals(new LaunchImage("sha256:index", "sha256:index"),
                newService(daemon.client).resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.PREFER_CACHED));
    }

    @Test
    void aCachedLaunchDoesNotReportTheDigestOfAnImageTheTagNoLongerNames() {
        FakeDaemon daemon = new FakeDaemon();
        daemon.registry.put(REGISTRY_IMAGE, image("sha256:old", REGISTRY_REPO + "@sha256:old-manifest"));
        daemon.registryDigests.put(REGISTRY_IMAGE, "sha256:old-manifest");
        ImageCacheService service = newService(daemon.client);
        service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.DEFAULT);

        daemon.store(REGISTRY_IMAGE, image("sha256:rebuilt"));

        assertEquals(new LaunchImage("sha256:rebuilt", null),
                service.resolveForLaunch(REGISTRY_IMAGE, ImagePullBehavior.PREFER_CACHED));
    }

    @Test
    void manifestDigestIsEmptyWhenTheRepositoryHasSeveralDigests() {
        InspectImageResponse image = image("sha256:id",
                REGISTRY_REPO + "@sha256:platform-manifest", REGISTRY_REPO + "@sha256:index");

        assertEquals(Optional.empty(), ImageCacheService.manifestDigest(REGISTRY_IMAGE, image));
    }

    @Test
    void manifestDigestIsTheRepoDigestOfTheRepositoryTheReferenceNames() {
        InspectImageResponse pulled = image("sha256:id",
                "mirror.example:5000/app@sha256:mirror-manifest", REGISTRY_REPO + "@sha256:manifest");

        assertEquals(Optional.of("sha256:manifest"), ImageCacheService.manifestDigest(REGISTRY_IMAGE, pulled));
    }

    @Test
    void manifestDigestMatchesDockerHubRepositoriesWrittenInFull() {
        InspectImageResponse pulled = image("sha256:id", "alpine@sha256:manifest");

        assertEquals(Optional.of("sha256:manifest"),
                ImageCacheService.manifestDigest("docker.io/library/alpine:3.20", pulled));
    }

    @Test
    void manifestDigestOfADigestReferenceIsThatDigest() {
        assertEquals(Optional.of("sha256:pinned"),
                ImageCacheService.manifestDigest(REGISTRY_REPO + "@sha256:pinned", image("sha256:id")));
    }

    @Test
    void manifestDigestIsEmptyForALocallyBuiltImage() {
        assertEquals(Optional.empty(), ImageCacheService.manifestDigest(LOCAL_IMAGE, image("sha256:built")));
    }

    private static InspectImageResponse image(String id, String... repoDigests) {
        return new InspectImageResponse()
                .withId(id)
                .withOs("linux")
                .withArch("amd64")
                .withRepoDigests(List.of(repoDigests));
    }

    /**
     * A daemon whose local images and registry are plain maps, so a test can move a tag in either
     * place and watch which image the next launch resolves to.
     */
    private static final class FakeDaemon {
        final DockerClient client = mock(DockerClient.class);
        final Map<String, InspectImageResponse> local = new HashMap<>();
        final Map<String, InspectImageResponse> registry = new HashMap<>();
        final Map<String, String> registryDigests = new HashMap<>();
        final AtomicInteger pulls = new AtomicInteger();

        FakeDaemon() {
            when(client.inspectImageCmd(anyString())).thenAnswer(invocation -> {
                String reference = invocation.getArgument(0);
                InspectImageCmd inspect = mock(InspectImageCmd.class);
                when(inspect.exec()).thenAnswer(ignored -> {
                    InspectImageResponse image = local.get(reference);
                    if (image == null) {
                        throw new NotFoundException("No such image: " + reference);
                    }
                    return image;
                });
                return inspect;
            });
            when(client.pullImageCmd(anyString())).thenAnswer(invocation -> {
                String reference = invocation.getArgument(0);
                PullImageCmd pull = mock(PullImageCmd.class);
                when(pull.withAuthConfig(any())).thenReturn(pull);
                when(pull.exec(any(PullImageResultCallback.class))).thenAnswer(execution -> {
                    pulls.incrementAndGet();
                    InspectImageResponse image = registry.get(reference);
                    if (image == null) {
                        throw new NotFoundException("manifest unknown: " + reference);
                    }
                    store(reference, image);
                    String digest = registryDigests.get(reference);
                    if (digest != null) {
                        PullResponseItem status = mock(PullResponseItem.class);
                        when(status.getStatus()).thenReturn("Digest: " + digest);
                        execution.<PullImageResultCallback>getArgument(0).onNext(status);
                    }
                    return mock(PullImageResultCallback.class);
                });
                return pull;
            });
        }

        void store(String tag, InspectImageResponse image) {
            local.put(tag, image);
            local.put(image.getId(), image);
        }
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
