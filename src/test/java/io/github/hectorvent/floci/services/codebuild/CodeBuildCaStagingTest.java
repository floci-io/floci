package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CopyArchiveToContainerCmd;
import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Staging the Floci CA certificate into a build container copies the PEM over the shared docker
 * socket. Under an LZA Deploy fan-out that socket drops the write mid-stream ({@code Broken pipe}),
 * exactly as {@code copySourceToContainer} guards against. If the copy is fired once and its failure
 * swallowed, the container runs CA-less and every spoofed HTTPS AWS call dies with
 * {@code DEPTH_ZERO_SELF_SIGNED_CERT} three seconds later — a cryptic failure far from the cause.
 * Staging must therefore retry a transient docker I/O error and fail the build loudly when it cannot
 * stage the cert, never proceed silently without it.
 */
class CodeBuildCaStagingTest {

    private CodeBuildRunner runner(DockerClient docker, Path certFile, Path persistent) {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.tls().certPath()).thenReturn(Optional.of(certFile.toString()));
        when(config.storage().persistentPath()).thenReturn(persistent.toString());
        return new CodeBuildRunner(docker, null, null, null, null, null, null, null, config, null, null);
    }

    private DockerClient dockerWhoseCopyExec(AtomicInteger execCalls,
                                             java.util.function.IntFunction<Void> behaviour) {
        CopyArchiveToContainerCmd cmd = mock(CopyArchiveToContainerCmd.class);
        when(cmd.withRemotePath(anyString())).thenReturn(cmd);
        when(cmd.withTarInputStream(any())).thenReturn(cmd);
        when(cmd.exec()).thenAnswer(inv -> behaviour.apply(execCalls.incrementAndGet()));
        DockerClient docker = mock(DockerClient.class);
        when(docker.copyArchiveToContainerCmd(anyString())).thenReturn(cmd);
        return docker;
    }

    @Test
    void stagingRetriesTransientBrokenPipeThenSucceeds(@TempDir Path dir) throws Exception {
        Path cert = Files.writeString(dir.resolve("floci-selfsigned.crt"), "-----BEGIN CERTIFICATE-----\nx\n-----END CERTIFICATE-----\n");
        AtomicInteger execCalls = new AtomicInteger();
        DockerClient docker = dockerWhoseCopyExec(execCalls, attempt -> {
            if (attempt < 3) {
                throw new RuntimeException(new IOException("Broken pipe"));
            }
            return null;
        });
        runner(docker, cert, dir).stageCaCertificate("container-abc");
        assertEquals(3, execCalls.get(),
                "staging must retry a transient Broken pipe, not fire the copy exactly once");
    }

    @Test
    void stagingThrowsWhenTransientFailurePersists(@TempDir Path dir) throws Exception {
        Path cert = Files.writeString(dir.resolve("floci-selfsigned.crt"), "-----BEGIN CERTIFICATE-----\nx\n-----END CERTIFICATE-----\n");
        AtomicInteger execCalls = new AtomicInteger();
        DockerClient docker = dockerWhoseCopyExec(execCalls, attempt -> {
            throw new RuntimeException(new IOException("Broken pipe"));
        });
        assertThrows(RuntimeException.class,
                () -> runner(docker, cert, dir).stageCaCertificate("container-xyz"),
                "an un-stageable cert must fail the build loudly, never proceed CA-less and silent");
        assertEquals(CodeBuildRunner.SOURCE_COPY_MAX_ATTEMPTS, execCalls.get(),
                "staging must exhaust the shared source-copy retry budget before giving up");
    }

    @Test
    void stagingThrowsWhenCertFileIsUnreadable(@TempDir Path dir) {
        // stageCaCertificate is only ever reached under spoofedEndpointTrustEnabled(), so the CA is
        // required — an unreadable/missing cert is a hard failure, not a warn-and-continue. Proceeding
        // CA-less lets the build start, then every spoofed HTTPS AWS call dies three seconds later with
        // DEPTH_ZERO_SELF_SIGNED_CERT far from the cause. Fail loud here, matching the catch block below.
        Path missing = dir.resolve("floci-selfsigned.crt"); // never written -> not readable
        AtomicInteger execCalls = new AtomicInteger();
        DockerClient docker = dockerWhoseCopyExec(execCalls, attempt -> null);
        assertThrows(RuntimeException.class,
                () -> runner(docker, missing, dir).stageCaCertificate("container-noca"),
                "an unreadable CA cert must fail the build loudly, never warn-and-proceed CA-less");
        assertEquals(0, execCalls.get(),
                "an unreadable cert must fail before any copy is attempted, not after a partial stage");
    }
}
