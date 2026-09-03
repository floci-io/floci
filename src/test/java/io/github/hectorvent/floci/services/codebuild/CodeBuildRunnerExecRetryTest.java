package io.github.hectorvent.floci.services.codebuild;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ExecCreateCmd;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.command.InspectExecCmd;
import com.github.dockerjava.api.command.InspectExecResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The transport-level retry ({@code RetryingDockerHttpClient}) excludes every {@code /exec} path
 * because exec-START must never be replayed. That leaves exec BOOKKEEPING — exec-create (replay
 * leaves at most an orphaned, never-started exec instance) and exec-inspect (a read) — exposed to
 * the same transient broken-pipe blips as every other short docker call. A blip on the
 * exit-code inspect after a fully successful build silently failed LZA Bootstrap builds: every
 * phase SUCCEEDED, the terminal status FAILED, and nothing was logged. These call sites retry
 * for themselves, like the CA-staging copy already does.
 */
class CodeBuildRunnerExecRetryTest {

    private static final int MAX_ATTEMPTS = 3;

    private static RuntimeException brokenPipe() {
        return new RuntimeException(new IOException("Broken pipe"));
    }

    private CodeBuildRunner runnerWith(DockerClient docker) {
        return new CodeBuildRunner(docker, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void fetchExecExitCodeRetriesTransientBrokenPipe() {
        DockerClient docker = mock(DockerClient.class);
        InspectExecCmd cmd = mock(InspectExecCmd.class);
        InspectExecResponse response = mock(InspectExecResponse.class);
        when(response.getExitCodeLong()).thenReturn(0L);
        AtomicInteger calls = new AtomicInteger();
        when(cmd.exec()).thenAnswer(inv -> {
            if (calls.incrementAndGet() < 3) {
                throw brokenPipe();
            }
            return response;
        });
        when(docker.inspectExecCmd("exec-1")).thenReturn(cmd);

        Long exitCode = runnerWith(docker).fetchExecExitCode("exec-1", MAX_ATTEMPTS, 0L);

        assertEquals(0L, exitCode,
                "a broken pipe on the exit-code read is a socket blip, not a build failure");
        assertEquals(3, calls.get());
    }

    @Test
    void fetchExecExitCodeDoesNotRetryDaemonRejection() {
        DockerClient docker = mock(DockerClient.class);
        InspectExecCmd cmd = mock(InspectExecCmd.class);
        RuntimeException noSuchExec = new RuntimeException("No such exec instance");
        AtomicInteger calls = new AtomicInteger();
        when(cmd.exec()).thenAnswer(inv -> {
            calls.incrementAndGet();
            throw noSuchExec;
        });
        when(docker.inspectExecCmd("exec-1")).thenReturn(cmd);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> runnerWith(docker).fetchExecExitCode("exec-1", MAX_ATTEMPTS, 0L));

        assertSame(noSuchExec, thrown, "a genuine daemon rejection must surface unchanged");
        assertEquals(1, calls.get());
    }

    @Test
    void createPhaseExecRetriesTransientBrokenPipe() {
        DockerClient docker = mock(DockerClient.class);
        ExecCreateCmd cmd = mock(ExecCreateCmd.class, RETURNS_SELF);
        ExecCreateCmdResponse created = mock(ExecCreateCmdResponse.class);
        when(created.getId()).thenReturn("exec-9");
        AtomicInteger calls = new AtomicInteger();
        when(cmd.exec()).thenAnswer(inv -> {
            if (calls.incrementAndGet() == 1) {
                throw brokenPipe();
            }
            return created;
        });
        when(docker.execCreateCmd(any())).thenReturn(cmd);

        String execId = runnerWith(docker).createPhaseExec(
                "container-abc", "/work", List.of("A=1"), new String[]{"sh", "-c", "true"},
                MAX_ATTEMPTS, 0L);

        assertEquals("exec-9", execId,
                "a replayed exec-create leaves at most an orphaned never-started exec instance;"
                        + " it is safe to retry and must be");
        assertEquals(2, calls.get());
    }
}
