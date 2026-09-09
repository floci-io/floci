package io.github.hectorvent.floci.services.lambda.launcher;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.core.common.docker.ContainerLifecycleManager;
import io.github.hectorvent.floci.core.common.docker.ContainerLogStreamer;
import io.github.hectorvent.floci.core.common.docker.DockerHostResolver;
import io.github.hectorvent.floci.core.common.docker.LaunchedContainerAwsEnv;
import io.github.hectorvent.floci.services.ecr.registry.EcrRegistryManager;
import io.github.hectorvent.floci.services.lambda.LambdaLayerService;
import io.github.hectorvent.floci.services.lambda.model.LambdaFunction;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContainerLauncher#ensureCodeVolume} reuse across restarts. A code volume is
 * content-addressed by the function's codeSha256 and survives an emulator restart, but the in-process
 * "already populated" set does not. A persisted completion marker lets a later run recognize the
 * populated volume and mount it as-is instead of re-copying its (e.g. ~34k) files.
 *
 * <p>Uses a subclass that overrides {@code populateCodeVolume} (rather than a Mockito spy) so the
 * internal self-call from {@code ensureCodeVolume} is intercepted — a spy would not intercept it.
 */
class ContainerLauncherCodeVolumeReuseTest {

    @TempDir
    Path tempDir;

    private ContainerLifecycleManager lifecycleManager;
    private RecordingLauncher launcher;

    /** Records populate calls instead of running the real Docker helper-container copy. */
    private static final class RecordingLauncher extends ContainerLauncher {
        final List<String> populated = new ArrayList<>();
        Runnable beforePopulate = () -> {};
        RuntimeException populateFailure;

        RecordingLauncher(ContainerLifecycleManager lifecycleManager, EmulatorConfig config) {
            super(mock(ContainerBuilder.class), lifecycleManager, mock(ContainerLogStreamer.class),
                    mock(ImageResolver.class), mock(RuntimeApiServerFactory.class),
                    mock(DockerHostResolver.class), config, mock(EcrRegistryManager.class),
                    mock(LambdaLayerService.class), mock(LaunchedContainerAwsEnv.class),
                    mock(LambdaExecutionRoleCredentials.class));
        }

        @Override
        void populateCodeVolume(String volName, LambdaFunction fn, String image) {
            beforePopulate.run();
            if (populateFailure != null) {
                throw populateFailure;
            }
            populated.add(volName);
        }
    }

    @BeforeEach
    void setUp() {
        lifecycleManager = mock(ContainerLifecycleManager.class);
        EmulatorConfig config = mock(EmulatorConfig.class);
        EmulatorConfig.StorageConfig storage = mock(EmulatorConfig.StorageConfig.class);
        EmulatorConfig.ServicesConfig services = mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.LambdaServiceConfig lambda = mock(EmulatorConfig.LambdaServiceConfig.class);
        when(config.storage()).thenReturn(storage);
        when(storage.persistentPath()).thenReturn(tempDir.toString());
        // ensureCodeVolume resolves the configured container-name prefix (unset = "floci").
        when(config.services()).thenReturn(services);
        when(services.lambda()).thenReturn(lambda);
        when(lambda.containerNamePrefix()).thenReturn(Optional.empty());
        launcher = new RecordingLauncher(lifecycleManager, config);
    }

    private static LambdaFunction fn(String sha) {
        LambdaFunction fn = new LambdaFunction();
        fn.setFunctionName("orders");
        fn.setCodeSha256(sha);
        return fn;
    }

    private void writeMarker(String vol) throws Exception {
        Path dir = tempDir.resolve("lambda-codevol-markers");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(vol), "");
    }

    @Test
    void reusesExistingVolumeWhenMarkerPresentAndVolumeExists() throws Exception {
        LambdaFunction fn = fn("sha-v1-abcdef0123456789");
        String vol = ContainerLauncher.codeVolumeName(fn);
        writeMarker(vol);
        when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.of(Set.of(vol)));

        String result = launcher.ensureCodeVolume(fn, "public.ecr.aws/lambda/nodejs:20");

        assertEquals(vol, result);
        assertTrue(launcher.populated.isEmpty(),
                "the ~34k-file populate/copy must be skipped when a previously-populated volume is reused");
    }

    @Test
    void populatesAndWritesMarkerWhenMarkerAbsent() throws Exception {
        LambdaFunction fn = fn("sha-v1-abcdef0123456789");
        String vol = ContainerLauncher.codeVolumeName(fn);
        // Volume exists but no completion marker (e.g. a prior populate crashed mid-copy): re-populate.

        String result = launcher.ensureCodeVolume(fn, "img");

        assertEquals(vol, result);
        assertEquals(List.of(vol), launcher.populated, "populate must run when no completion marker exists");
        assertTrue(Files.isRegularFile(tempDir.resolve("lambda-codevol-markers").resolve(vol)),
                "a completion marker must be written after a successful populate so the next boot reuses it");
    }

    @Test
    void deletesStaleMarkerBeforeRepopulatingMissingVolume() throws Exception {
        LambdaFunction fn = fn("sha-v1-abcdef0123456789");
        String vol = ContainerLauncher.codeVolumeName(fn);
        Path marker = tempDir.resolve("lambda-codevol-markers").resolve(vol);
        writeMarker(vol);
        // The marker lingers but the volume was pruned; reusing it would mount an empty /var/task.
        when(lifecycleManager.tryListVolumeNames())
                .thenReturn(Optional.of(Set.of()), Optional.of(Set.of(vol)));
        launcher.beforePopulate = () -> assertFalse(Files.exists(marker),
                "the stale completion marker must be deleted before repopulation starts");

        launcher.ensureCodeVolume(fn, "img");

        assertEquals(List.of(vol), launcher.populated,
                "a lingering marker without the backing volume must not trigger reuse");
        assertTrue(Files.isRegularFile(marker), "successful repopulation must write a fresh marker");
    }

    @Test
    void failedRepopulateCannotLeaveOldCompletionMarker() throws Exception {
        LambdaFunction fn = fn("sha-v1-abcdef0123456789");
        String vol = ContainerLauncher.codeVolumeName(fn);
        Path marker = tempDir.resolve("lambda-codevol-markers").resolve(vol);
        writeMarker(vol);
        when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.of(Set.of()));
        launcher.populateFailure = new IllegalStateException("copy failed");

        assertThrows(IllegalStateException.class, () -> launcher.ensureCodeVolume(fn, "img"));

        assertFalse(Files.exists(marker),
                "a failed repopulation must not retain the previous run's completion marker");
    }

    @Test
    void volumeInventoryFailureRepopulatesAfterInvalidatingMarker() throws Exception {
        LambdaFunction fn = fn("sha-v1-abcdef0123456789");
        String vol = ContainerLauncher.codeVolumeName(fn);
        Path marker = tempDir.resolve("lambda-codevol-markers").resolve(vol);
        writeMarker(vol);
        when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.empty());
        launcher.beforePopulate = () -> assertFalse(Files.exists(marker),
                "the old completion marker must be removed before fallback population starts");

        String result = launcher.ensureCodeVolume(fn, "img");

        assertEquals(vol, result);
        assertEquals(List.of(vol), launcher.populated,
                "an unavailable inventory must degrade to the safe population path");
        assertTrue(Files.isRegularFile(marker),
                "successful fallback population must write a fresh completion marker");
    }

    @Test
    void failedInventoryFallbackCannotLeaveOldCompletionMarker() throws Exception {
        LambdaFunction fn = fn("sha-v1-abcdef0123456789");
        String vol = ContainerLauncher.codeVolumeName(fn);
        Path marker = tempDir.resolve("lambda-codevol-markers").resolve(vol);
        writeMarker(vol);
        when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.empty());
        launcher.populateFailure = new IllegalStateException("copy failed");

        assertThrows(IllegalStateException.class, () -> launcher.ensureCodeVolume(fn, "img"));

        assertFalse(Files.exists(marker),
                "a failed fallback population must not retain the previous completion marker");
        assertTrue(launcher.populated.isEmpty());
    }

    @Test
    void successfulPopulationPrunesOnlyOrphanInternalMarkers() throws Exception {
        LambdaFunction fn = fn("sha-v1-abcdef0123456789");
        String vol = ContainerLauncher.codeVolumeName(fn);
        String live = "floci-code-live";
        String orphan = "floci-code-orphan";
        writeMarker(live);
        writeMarker(orphan);
        writeMarker("unrelated-marker");
        when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.of(Set.of(vol, live)));

        launcher.ensureCodeVolume(fn, "img");

        Path markerDir = tempDir.resolve("lambda-codevol-markers");
        assertTrue(Files.isRegularFile(markerDir.resolve(vol)));
        assertTrue(Files.isRegularFile(markerDir.resolve(live)));
        assertFalse(Files.exists(markerDir.resolve(orphan)));
        assertTrue(Files.isRegularFile(markerDir.resolve("unrelated-marker")),
                "marker cleanup must not delete files outside Floci's code-volume namespace");
    }

    @Test
    void successfulPopulationReplacesSymlinkMarkerWithoutFollowingIt() throws Exception {
        LambdaFunction fn = fn("sha-v1-abcdef0123456789");
        String vol = ContainerLauncher.codeVolumeName(fn);
        Path markerDir = tempDir.resolve("lambda-codevol-markers");
        Path marker = markerDir.resolve(vol);
        Path symlinkTarget = tempDir.resolve("outside-marker-target");
        Files.createDirectories(markerDir);
        Files.writeString(symlinkTarget, "keep");
        Files.createSymbolicLink(marker, symlinkTarget);
        when(lifecycleManager.tryListVolumeNames()).thenReturn(Optional.of(Set.of(vol)));

        launcher.ensureCodeVolume(fn, "img");

        assertEquals("keep", Files.readString(symlinkTarget),
                "writing a completion marker must not follow and truncate a pre-existing symlink");
        assertTrue(Files.isRegularFile(marker, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                "successful population should replace the symlink with a real marker file");
        assertFalse(Files.isSymbolicLink(marker));
    }

    @Test
    void partialTarProducerFailureIsStrictOnlyForCodeVolumePopulation() throws Exception {
        DockerClient dockerClient = mock(DockerClient.class);
        CopyArchiveToContainerCmd copyCommand = mock(CopyArchiveToContainerCmd.class);
        java.io.InputStream[] capturedInput = {null};
        when(dockerClient.copyArchiveToContainerCmd("container-123")).thenReturn(copyCommand);
        when(copyCommand.withRemotePath("/var/task")).thenReturn(copyCommand);
        when(copyCommand.withTarInputStream(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    capturedInput[0] = invocation.getArgument(0);
                    return copyCommand;
                });
        org.mockito.Mockito.doAnswer(invocation -> {
            capturedInput[0].transferTo(java.io.OutputStream.nullOutputStream());
            return null;
        }).when(copyCommand).exec();

        assertDoesNotThrow(() -> ContainerLauncher.copyDirToContainer(
                dockerClient, "container-123", tempDir, "/var/task", "orders",
                (sourceDir, out) -> {
                    out.write(new byte[]{1, 2, 3});
                    throw new java.io.IOException("ordinary copy producer closed");
                }));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> ContainerLauncher.copyDirToContainerStrict(
                        dockerClient, "container-123", tempDir, "/var/task", "orders",
                        (sourceDir, out) -> {
                            out.write(new byte[]{1, 2, 3});
                            throw new java.io.IOException("producer failed after partial archive");
                        }));

        assertTrue(failure.getMessage().contains("Failed to copy directory"), failure.getMessage());
        assertTrue(failure.getCause().getMessage().contains("Failed to stream tar"), failure.toString());
    }
}
