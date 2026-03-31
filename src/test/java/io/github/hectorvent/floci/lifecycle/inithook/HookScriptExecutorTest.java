package io.github.hectorvent.floci.lifecycle.inithook;

import io.github.hectorvent.floci.config.EmulatorConfig;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class HookScriptExecutorTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private EmulatorConfig emulatorConfigMock;

    @InjectMocks
    private HookScriptExecutor hookScriptExecutor;

    @Test
    @DisplayName("Should complete when process exits successfully")
    void shouldCompleteWhenProcessExitsSuccessfully() throws InterruptedException {
        Process process = Mockito.mock(Process.class);

        Mockito.when(emulatorConfigMock.initHooks().timeoutSeconds()).thenReturn(30L);
        Mockito.when(process.waitFor(30L, TimeUnit.SECONDS)).thenReturn(true);
        Mockito.when(process.exitValue()).thenReturn(0);
        Mockito.when(process.isAlive()).thenReturn(false);

        Assertions.assertDoesNotThrow(() -> hookScriptExecutor.run(process, "script.sh"));

        Mockito.verify(process).waitFor(30L, TimeUnit.SECONDS);
        Mockito.verify(process).exitValue();
        Mockito.verify(process, Mockito.never()).destroy();
        Mockito.verify(process, Mockito.never()).destroyForcibly();
    }

    @Test
    @DisplayName("Should throw when process exits with a non-zero code")
    void shouldThrowWhenProcessExitsWithNonZeroCode() throws InterruptedException {
        Process process = Mockito.mock(Process.class);

        Mockito.when(emulatorConfigMock.initHooks().timeoutSeconds()).thenReturn(30L);
        Mockito.when(process.waitFor(30L, TimeUnit.SECONDS)).thenReturn(true);
        Mockito.when(process.exitValue()).thenReturn(42);
        Mockito.when(process.isAlive()).thenReturn(false);

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> hookScriptExecutor.run(process, "script.sh"));

        Mockito.verify(process).exitValue();
        Mockito.verify(process, Mockito.never()).destroy();

        Assertions.assertAll(
                () -> Assertions.assertEquals("Hook script failed: script.sh exited with code 42", exception.getMessage()),
                () -> Assertions.assertNull(exception.getCause())
        );
    }

    @Test
    @DisplayName("Should terminate process and throw when process times out")
    void shouldTerminateProcessAndThrowWhenProcessTimesOut() throws InterruptedException {
        Process process = Mockito.mock(Process.class);

        Mockito.when(emulatorConfigMock.initHooks().timeoutSeconds()).thenReturn(30L);
        Mockito.when(emulatorConfigMock.initHooks().shutdownGracePeriodSeconds()).thenReturn(2L);
        Mockito.when(process.waitFor(30L, TimeUnit.SECONDS)).thenReturn(false);
        Mockito.when(process.isAlive()).thenReturn(true, false);
        Mockito.when(process.waitFor(2L, TimeUnit.SECONDS)).thenReturn(false);

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> hookScriptExecutor.run(process, "script.sh"));

        Mockito.verify(process).destroy();
        Mockito.verify(process, times(2)).waitFor(2L, TimeUnit.SECONDS);
        Mockito.verify(process).destroyForcibly();

        Assertions.assertAll(
                () -> Assertions.assertEquals("Hook script timed out after 30 seconds: script.sh", exception.getMessage()),
                () -> Assertions.assertNull(exception.getCause())
        );
    }

    @Test
    @DisplayName("Should not force kill when process terminates during grace period")
    void shouldNotForceKillWhenProcessTerminatesDuringGracePeriod() throws InterruptedException {
        Process process = Mockito.mock(Process.class);

        Mockito.when(emulatorConfigMock.initHooks().timeoutSeconds()).thenReturn(30L);
        Mockito.when(emulatorConfigMock.initHooks().shutdownGracePeriodSeconds()).thenReturn(2L);
        Mockito.when(process.waitFor(30L, TimeUnit.SECONDS)).thenReturn(false);
        Mockito.when(process.isAlive()).thenReturn(true, false);
        Mockito.when(process.waitFor(2L, TimeUnit.SECONDS)).thenReturn(true);

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> hookScriptExecutor.run(process, "script.sh"));

        Mockito.verify(process).destroy();
        Mockito.verify(process, Mockito.never()).destroyForcibly();

        Assertions.assertAll(
                () -> Assertions.assertEquals("Hook script timed out after 30 seconds: script.sh", exception.getMessage()),
                () -> Assertions.assertNull(exception.getCause())
        );
    }

    @Test
    @DisplayName("Should force cleanup when interrupted while waiting")
    void shouldForceCleanupWhenInterruptedWhileWaiting() throws InterruptedException {
        Process process = Mockito.mock(Process.class);

        Mockito.when(emulatorConfigMock.initHooks().timeoutSeconds()).thenReturn(30L);
        InterruptedException exception = new InterruptedException("boom");
        Mockito.when(process.waitFor(30L, TimeUnit.SECONDS)).thenThrow(exception);
        Mockito.when(process.isAlive()).thenReturn(true);

        InterruptedException thrown = Assertions.assertThrows(InterruptedException.class, () -> hookScriptExecutor.run(process, "script.sh"));

        Assertions.assertSame(exception, thrown);
        Mockito.verify(process).destroyForcibly();
    }

    @Test
    @DisplayName("Should force cleanup when process is still alive in finally")
    void shouldForceCleanupWhenProcessIsStillAliveInFinally() throws InterruptedException {
        Process process = Mockito.mock(Process.class);

        Mockito.when(emulatorConfigMock.initHooks().timeoutSeconds()).thenReturn(30L);
        Mockito.when(process.waitFor(30L, TimeUnit.SECONDS)).thenReturn(true);
        Mockito.when(process.exitValue()).thenReturn(0);
        Mockito.when(process.isAlive()).thenReturn(true);

        Assertions.assertDoesNotThrow(() -> hookScriptExecutor.run(process, "script.sh"));

        Mockito.verify(process).destroyForcibly();
    }

    @Test
    @DisplayName("Should not wait for grace period when process stops immediately after destroy")
    void shouldNotWaitForGracePeriodWhenProcessStopsImmediatelyAfterDestroy() throws InterruptedException {
        Process process = Mockito.mock(Process.class);

        Mockito.when(emulatorConfigMock.initHooks().timeoutSeconds()).thenReturn(30L);
        Mockito.when(process.waitFor(30L, TimeUnit.SECONDS)).thenReturn(false);
        Mockito.when(process.isAlive()).thenReturn(false, false);

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> hookScriptExecutor.run(process, "script.sh"));

        Mockito.verify(process).destroy();
        Mockito.verify(process, Mockito.never()).waitFor(2L, TimeUnit.SECONDS);
        Mockito.verify(process, Mockito.never()).destroyForcibly();

        Assertions.assertAll(
                () -> Assertions.assertEquals("Hook script timed out after 30 seconds: script.sh", exception.getMessage()),
                () -> Assertions.assertNull(exception.getCause())
        );
    }

    @Test
    @DisplayName("Should throw IOException when shell executable does not exist")
    void shouldThrowIOExceptionWhenShellExecutableDoesNotExist() {
        File hookDirectory = new File(".");

        Mockito.when(emulatorConfigMock.initHooks().shellExecutable()).thenReturn("/definitely/missing/bash");

        IOException exception = Assertions.assertThrows(IOException.class, () -> hookScriptExecutor.run(hookDirectory, "script.sh"));

        Assertions.assertAll(
                () -> Assertions.assertNotNull(exception.getMessage()),
                () -> Assertions.assertTrue(exception.getMessage().startsWith("Cannot run program")),
                () -> Assertions.assertTrue(exception.getMessage().contains("\"/definitely/missing/bash\""))
        );
    }

}
