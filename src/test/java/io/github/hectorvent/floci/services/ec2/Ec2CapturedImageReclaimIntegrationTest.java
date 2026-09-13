package io.github.hectorvent.floci.services.ec2;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.model.Image;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.InstanceState;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CreateImage captures an instance's file system as a committed Docker image. Those layers are
 * real disk, and a build loop that re-creates the same AMI would otherwise leave one behind per
 * iteration, so deregistering an AMI releases its capture.
 *
 * <p>The exception is the case that matters for correctness: AWS keeps instances launched from a
 * deregistered AMI running, and lets them stop and start again. While anything can still boot
 * from a capture, the capture has to stay.
 *
 * <p>These drive the real CreateImage path rather than setting the captured reference by hand.
 * The image store hands back detached copies, so a mutation applied to a returned Image never
 * reaches the store -- a hand-built fixture silently tests nothing.
 */
@QuarkusTest
@TestProfile(Ec2CapturedImageReclaimIntegrationTest.ContainerBackedEc2.class)
class Ec2CapturedImageReclaimIntegrationTest {

    /**
     * The shared test configuration runs EC2 in mock mode, which skips container work entirely --
     * including the capture and its reclamation, so neither path would execute. This turns it
     * back on; the container manager itself is mocked, so no Docker is involved.
     */
    public static class ContainerBackedEc2 implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.ec2.mock", "false");
        }
    }

    @Inject
    Ec2Service service;

    @InjectMock
    Ec2ContainerManager containerManager;

    /**
     * Mocked so the reference a launch resolves is observable, and so a launch can be suspended
     * inside the section that reads the AMI. The real resolver only maps an AMI id to a base
     * Docker image, which no assertion here depends on.
     */
    @InjectMock
    AmiImageResolver amiImageResolver;

    private static final String REGION = "us-east-1";
    private static final String WEST = "us-west-2";
    private static final String BASE_AMI = "ami-amazonlinux2023";
    private static final ResolvedAmiImage BASE_IMAGE = ResolvedAmiImage.minimal("floci/base:latest");

    @BeforeEach
    void resolveEveryAmiToABaseImage() {
        when(amiImageResolver.resolveImage(anyString())).thenReturn(BASE_IMAGE);
        when(containerManager.removeCommittedImage(anyString())).thenReturn(true);
    }

    @BeforeEach
    void terminationMarksInstancesTerminated() {
        // Outside mock mode Ec2Service delegates termination to the container manager, which is
        // mocked here, so without this an instance stays "running" in the store forever and any
        // assertion about terminated instances would be vacuous.
        doAnswer(invocation -> {
            invocation.<Instance>getArgument(0).setState(InstanceState.terminated());
            return null;
        }).when(containerManager).terminate(any(Instance.class));
    }

    private Instance launch(String imageId) {
        return launch(REGION, imageId);
    }

    private Instance launch(String region, String imageId) {
        return service.runInstances(region, imageId, "t3.micro", 1, 1,
                null, List.of(), null, null, List.of(), null, null)
                .getInstances().getFirst();
    }

    /** The image reference the container manager was asked to launch for the given instance. */
    private ResolvedAmiImage launchedImageOf(Instance instance) {
        ArgumentCaptor<ResolvedAmiImage> resolved = ArgumentCaptor.forClass(ResolvedAmiImage.class);
        verify(containerManager).launch(eq(instance), resolved.capture(), any(), anyString(), any());
        return resolved.getValue();
    }

    /** Runs an instance and captures it, with the commit stubbed to the given reference. */
    private Image captureAmi(String name, String tag) {
        when(containerManager.commitInstance(any(Instance.class), anyString())).thenReturn(tag);
        Instance source = launch(BASE_AMI);
        Image image = service.createImage(REGION, source.getInstanceId(), name, "captured", true);
        service.terminateInstances(REGION, List.of(source.getInstanceId()));
        return image;
    }

    @Test
    void deregisteringAnUnusedAmiReleasesItsCapture() {
        Image image = captureAmi("reclaim-unused", "floci-ami/ami-unused:latest");

        service.deregisterImage(REGION, image.getImageId(), false);

        verify(containerManager).removeCommittedImage("floci-ami/ami-unused:latest");
    }

    @Test
    void deregisteringAnAmiWithALiveInstanceKeepsItsCapture() {
        // Removing the layer this instance boots from would break the stop/start that AWS
        // explicitly still permits after deregistration.
        Image image = captureAmi("reclaim-inuse", "floci-ami/ami-inuse:latest");
        launch(image.getImageId());

        service.deregisterImage(REGION, image.getImageId(), false);

        verify(containerManager, never()).removeCommittedImage("floci-ami/ami-inuse:latest");
    }

    @Test
    void aTerminatedInstanceDoesNotPinACapture() {
        // Terminated instances can never boot again, so they must not keep the layer alive --
        // otherwise nothing is ever reclaimed in a long-running emulator.
        Image image = captureAmi("reclaim-terminated", "floci-ami/ami-terminated:latest");
        Instance launched = launch(image.getImageId());
        service.terminateInstances(REGION, List.of(launched.getInstanceId()));

        service.deregisterImage(REGION, image.getImageId(), false);

        verify(containerManager).removeCommittedImage("floci-ami/ami-terminated:latest");
    }

    @Test
    void deregisteringAnAmiThatWasNeverCapturedRemovesNothing() {
        // RegisterImage and catalog AMIs have no captured layer to release.
        Image image = service.registerImage(REGION, "reclaim-uncaptured", "plain",
                "x86_64", "/dev/xvda", List.of());

        service.deregisterImage(REGION, image.getImageId(), false);

        verify(containerManager, never()).removeCommittedImage(anyString());
    }

    // ─── The copy carries the capture ─────────────────────────────────────────

    @Test
    void aCopiedAmiLaunchesTheCapturedFileSystemRatherThanTheBaseImage() {
        // The ancestry a copy inherits is flattened to a launchable catalog id, and the
        // CreateImage chain in between lives in the source region where the copy cannot see it.
        // Without the capture reference itself being copied, launching the copy silently starts
        // the base image and everything the source AMI captured is gone.
        String tag = "floci-ami/ami-copied:latest";
        Image source = captureAmi("copy-carries-capture", tag);

        Image copy = service.copyImage(WEST, REGION, source.getImageId(),
                uniqueName("copy-carries-capture-west"), "copied");
        Instance fromCopy = launch(WEST, copy.getImageId());

        assertEquals(tag, launchedImageOf(fromCopy).dockerImage());
    }

    @Test
    void deregisteringTheSourceKeepsACaptureItsCopyStillCarries() {
        // The copy shares the layer rather than duplicating it, so releasing it here would leave
        // a live AMI in another region whose file system had been deleted.
        String tag = "floci-ami/ami-copy-shared:latest";
        Image source = captureAmi("copy-shared-capture", tag);
        service.copyImage(WEST, REGION, source.getImageId(),
                uniqueName("copy-shared-capture-west"), "copied");

        service.deregisterImage(REGION, source.getImageId(), false);

        verify(containerManager, never()).removeCommittedImage(tag);
    }

    // ─── A capture that fails is not an available AMI ─────────────────────────

    @Test
    void aCaptureThatFailsFailsCreateImageRatherThanProducingAnEmptyAmi() {
        // An AMI with no captured file system launches its ancestor. Returning one here would
        // report success and hand back an image that does not contain what was asked for.
        when(containerManager.commitInstance(any(Instance.class), anyString()))
                .thenThrow(new Ec2ContainerManager.CaptureFailedException("daemon is unavailable", null));
        Instance source = launch(BASE_AMI);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createImage(REGION, source.getInstanceId(), uniqueName("failed-capture"),
                        "captured", true));

        assertEquals("InternalError", failure.getErrorCode());
    }

    @Test
    void anInstanceWithNoContainerToCaptureIsRejected() {
        // Nothing to commit means nothing to capture, and an AMI recorded anyway would boot the
        // ancestor. AWS reports CreateImage against an instance that is not running or stopped as
        // IncorrectInstanceState, which is the same condition seen from here.
        when(containerManager.commitInstance(any(Instance.class), anyString())).thenReturn(null);
        Instance source = launch(BASE_AMI);

        AwsException failure = assertThrows(AwsException.class, () ->
                service.createImage(REGION, source.getInstanceId(), uniqueName("no-container"),
                        "captured", true));

        assertEquals("IncorrectInstanceState", failure.getErrorCode());
    }

    @Test
    void anAmiWhoseCaptureFailedDoesNotHoldOnToItsName() {
        // The AMI record is created before the capture is attempted. Left behind, it would meet
        // the retry with InvalidAMIName.Duplicate against an image nothing can describe.
        String name = uniqueName("failed-capture-name");
        when(containerManager.commitInstance(any(Instance.class), anyString()))
                .thenThrow(new Ec2ContainerManager.CaptureFailedException("daemon is unavailable", null));
        Instance source = launch(BASE_AMI);
        assertThrows(AwsException.class, () ->
                service.createImage(REGION, source.getInstanceId(), name, "captured", true));

        when(containerManager.commitInstance(any(Instance.class), anyString()))
                .thenReturn("floci-ami/ami-retry:latest");
        Image retried = service.createImage(REGION, source.getInstanceId(), name, "captured", true);

        assertEquals(name, retried.getName());
    }

    // ─── A failed removal keeps the reference ─────────────────────────────────

    @Test
    void aRemovalTheDaemonRefusesKeepsTheReferenceForALaterAttempt() {
        // Deregistration is rejected the second time and nothing else can rediscover the tag, so
        // clearing the reference on a transient docker failure leaks the layer permanently. The
        // second termination is the later attempt: it only reaches the daemon at all if the
        // reference survived the first failure.
        String tag = "floci-ami/ami-removal-refused:latest";
        Image image = captureAmi("removal-refused", tag);
        Instance dependent = launch(image.getImageId());
        service.deregisterImage(REGION, image.getImageId(), false);
        verify(containerManager, never()).removeCommittedImage(tag);

        when(containerManager.removeCommittedImage(tag)).thenReturn(false);
        service.terminateInstances(REGION, List.of(dependent.getInstanceId()));
        verify(containerManager, times(1)).removeCommittedImage(tag);

        when(containerManager.removeCommittedImage(tag)).thenReturn(true);
        service.terminateInstances(REGION, List.of(dependent.getInstanceId()));

        verify(containerManager, times(2)).removeCommittedImage(tag);
    }

    // ─── Termination reclaims what deregistration had to retain ───────────────

    @Test
    void terminatingTheLastDependentInstanceReleasesARetainedCapture() {
        // Deregistration is the only other place a capture is reclaimed and it is rejected the
        // second time, so a capture retained for a live instance would otherwise stay on disk for
        // the lifetime of the emulator.
        String tag = "floci-ami/ami-retained:latest";
        Image image = captureAmi("retained-then-terminated", tag);
        Instance dependent = launch(image.getImageId());
        service.deregisterImage(REGION, image.getImageId(), false);
        verify(containerManager, never()).removeCommittedImage(tag);

        service.terminateInstances(REGION, List.of(dependent.getInstanceId()));

        verify(containerManager).removeCommittedImage(tag);
    }

    @Test
    void terminatingOneOfTwoDependentsKeepsTheCapture() {
        String tag = "floci-ami/ami-two-dependents:latest";
        Image image = captureAmi("two-dependents", tag);
        Instance first = launch(image.getImageId());
        launch(image.getImageId());
        service.deregisterImage(REGION, image.getImageId(), false);

        service.terminateInstances(REGION, List.of(first.getInstanceId()));

        verify(containerManager, never()).removeCommittedImage(tag);
    }

    @Test
    void terminatingAnInstanceOfAStillRegisteredAmiKeepsTheCapture() {
        // The AMI can still be launched again; its capture is not garbage.
        String tag = "floci-ami/ami-still-registered:latest";
        Image image = captureAmi("still-registered", tag);
        Instance dependent = launch(image.getImageId());

        service.terminateInstances(REGION, List.of(dependent.getInstanceId()));

        verify(containerManager, never()).removeCommittedImage(tag);
    }

    // ─── The registry invariants are one lock ─────────────────────────────────

    @Test
    void deregistrationCannotReclaimACaptureALaunchHasAlreadyResolved() throws Exception {
        // The launch is suspended after it has resolved the AMI and before its instance is
        // stored. Deregistration decides whether to release the capture by scanning for live
        // instances, so if it can run in that window it sees none and deletes the layer the
        // launch is about to boot from.
        String tag = "floci-ami/ami-launch-race:latest";
        Image image = captureAmi("launch-race", tag);
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(amiImageResolver.resolveImage(anyString())).thenAnswer(invocation -> {
            resolving.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS), "the launch was never released");
            return BASE_IMAGE;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Instance> launching = pool.submit(() -> launch(image.getImageId()));
            assertTrue(resolving.await(30, TimeUnit.SECONDS), "the launch never reached the AMI");
            Future<?> deregistering = pool.submit(
                    () -> service.deregisterImage(REGION, image.getImageId(), false));

            assertThrows(TimeoutException.class, () -> deregistering.get(1, TimeUnit.SECONDS),
                    "deregistration ran while a launch was mid-resolution of the same AMI");
            release.countDown();
            launching.get(30, TimeUnit.SECONDS);
            deregistering.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // The launch's instance was published before deregistration could look, so the capture is
        // retained for it rather than deleted underneath it.
        verify(containerManager, never()).removeCommittedImage(tag);
    }

    @Test
    void registrationCannotSlipBetweenDeregistrationsSnapshotScanAndItsDelete() throws Exception {
        // DeregisterImage decides which snapshots to delete by scanning the other AMIs for shared
        // references. A registration that lands between that scan and the delete leaves a live
        // AMI whose backing snapshot has been removed, so the two must exclude each other. The
        // deregistration is suspended inside its critical section to check that they do.
        String tag = "floci-ami/ami-register-race:latest";
        Image image = captureAmi("register-race", tag);
        CountDownLatch inside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(containerManager.removeCommittedImage(tag)).thenAnswer(invocation -> {
            inside.countDown();
            assertTrue(release.await(30, TimeUnit.SECONDS), "the deregistration was never released");
            return true;
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> deregistering = pool.submit(
                    () -> service.deregisterImage(REGION, image.getImageId(), true));
            assertTrue(inside.await(30, TimeUnit.SECONDS), "deregistration never started");
            Future<Image> registering = pool.submit(() -> service.registerImage(
                    REGION, uniqueName("register-race-other"), "other", "x86_64", "/dev/xvda",
                    List.of()));

            assertThrows(TimeoutException.class, () -> registering.get(1, TimeUnit.SECONDS),
                    "registration ran inside a deregistration's critical section");
            release.countDown();
            deregistering.get(30, TimeUnit.SECONDS);
            assertTrue(registering.get(30, TimeUnit.SECONDS).getImageId().startsWith("ami-"));
        } finally {
            pool.shutdownNow();
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }
}
