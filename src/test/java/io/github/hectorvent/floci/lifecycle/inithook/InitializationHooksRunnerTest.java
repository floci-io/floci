package io.github.hectorvent.floci.lifecycle.inithook;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;

@ExtendWith(MockitoExtension.class)
class InitializationHooksRunnerTest {

    @Mock
    private HookScriptExecutor hookScriptExecutorMock;

    @InjectMocks
    private InitializationHooksRunner initializationHooksRunner;

    @Test
    @DisplayName("Should execute shell scripts in sorted order")
    void shouldExecuteShellScriptsInSortedOrder() throws IOException, InterruptedException {
        File hookDirectory = Mockito.mock(File.class);
        Mockito.when(hookDirectory.getAbsolutePath()).thenReturn("/hooks/startup");
        Mockito.when(hookDirectory.exists()).thenReturn(true);
        Mockito.when(hookDirectory.isDirectory()).thenReturn(true);
        Mockito.when(hookDirectory.list(ArgumentMatchers.any())).thenReturn(new String[]{"20-third.sh", "10-first.sh", "15-second.sh"});

        initializationHooksRunner.run("startup", hookDirectory);

        var inOrder = Mockito.inOrder(hookScriptExecutorMock);
        inOrder.verify(hookScriptExecutorMock).run(hookDirectory, "10-first.sh");
        inOrder.verify(hookScriptExecutorMock).run(hookDirectory, "15-second.sh");
        inOrder.verify(hookScriptExecutorMock).run(hookDirectory, "20-third.sh");
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("Should ignore directories without shell scripts")
    void shouldIgnoreDirectoriesWithoutShellScripts() throws IOException, InterruptedException {
        File hookDirectory = Mockito.mock(File.class);
        Mockito.when(hookDirectory.getAbsolutePath()).thenReturn("/hooks/startup");
        Mockito.when(hookDirectory.exists()).thenReturn(true);
        Mockito.when(hookDirectory.isDirectory()).thenReturn(true);
        Mockito.when(hookDirectory.list(ArgumentMatchers.any())).thenReturn(new String[0]);

        initializationHooksRunner.run("startup", hookDirectory);

        Mockito.verifyNoInteractions(hookScriptExecutorMock);
    }

    @Test
    @DisplayName("Should ignore hook directory when listing scripts returns null")
    void shouldIgnoreHookDirectoryWhenListingScriptsReturnsNull() throws IOException, InterruptedException {
        final File hookDirectory = Mockito.mock(File.class);
        Mockito.when(hookDirectory.getAbsolutePath()).thenReturn("/hooks/startup");
        Mockito.when(hookDirectory.exists()).thenReturn(true);
        Mockito.when(hookDirectory.isDirectory()).thenReturn(true);
        Mockito.when(hookDirectory.list(ArgumentMatchers.any())).thenReturn(null);

        initializationHooksRunner.run("startup", hookDirectory);

        Mockito.verifyNoInteractions(hookScriptExecutorMock);
    }

    @Test
    @DisplayName("Should ignore missing hook directory")
    void shouldIgnoreMissingHookDirectory() throws IOException, InterruptedException {
        File missingHookDirectory = Mockito.mock(File.class);
        Mockito.when(missingHookDirectory.getAbsolutePath()).thenReturn("/hooks/missing");
        Mockito.when(missingHookDirectory.exists()).thenReturn(false);

        initializationHooksRunner.run("startup", missingHookDirectory);

        Mockito.verifyNoInteractions(hookScriptExecutorMock);
    }

    @Test
    @DisplayName("Should ignore hook path that is not a directory")
    void shouldIgnoreHookPathThatIsNotADirectory() throws IOException, InterruptedException {
        File hookPath = Mockito.mock(File.class);
        Mockito.when(hookPath.getAbsolutePath()).thenReturn("/hooks/startup");
        Mockito.when(hookPath.exists()).thenReturn(true);
        Mockito.when(hookPath.isDirectory()).thenReturn(false);

        initializationHooksRunner.run("startup", hookPath);

        Mockito.verifyNoInteractions(hookScriptExecutorMock);
    }

    @Test
    @DisplayName("Should stop at first script failure")
    void shouldStopAtFirstScriptFailure() throws IOException, InterruptedException {
        File hookDirectory = Mockito.mock(File.class);
        Mockito.when(hookDirectory.getAbsolutePath()).thenReturn("/hooks/startup");
        Mockito.when(hookDirectory.exists()).thenReturn(true);
        Mockito.when(hookDirectory.isDirectory()).thenReturn(true);
        Mockito.when(hookDirectory.list(ArgumentMatchers.any())).thenReturn(new String[]{"10-first.sh", "20-failing.sh", "30-never-runs.sh"});

        Mockito.doNothing().when(hookScriptExecutorMock).run(hookDirectory, "10-first.sh");

        IllegalStateException illegalStateException = new IllegalStateException("Hook script failed: 20-failing.sh exited with code 127");
        Mockito.doThrow(illegalStateException).when(hookScriptExecutorMock).run(hookDirectory, "20-failing.sh");

        IllegalStateException exception = Assertions.assertThrows(IllegalStateException.class, () -> initializationHooksRunner.run("startup", hookDirectory));
        Assertions.assertSame(illegalStateException, exception);

        var inOrder = Mockito.inOrder(hookScriptExecutorMock);
        inOrder.verify(hookScriptExecutorMock).run(hookDirectory, "10-first.sh");
        inOrder.verify(hookScriptExecutorMock).run(hookDirectory, "20-failing.sh");
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("Should propagate IOException from hook script executor")
    void shouldPropagateIOExceptionFromHookScriptExecutor() throws IOException, InterruptedException {
        File hookDirectory = Mockito.mock(File.class);
        Mockito.when(hookDirectory.getAbsolutePath()).thenReturn("/hooks/startup");
        Mockito.when(hookDirectory.exists()).thenReturn(true);
        Mockito.when(hookDirectory.isDirectory()).thenReturn(true);
        Mockito.when(hookDirectory.list(ArgumentMatchers.any())).thenReturn(new String[]{"10-bootstrap.sh"});

        IOException ioException = new IOException("Cannot execute hook script");
        Mockito.doThrow(ioException).when(hookScriptExecutorMock).run(hookDirectory, "10-bootstrap.sh");

        IOException exception = Assertions.assertThrows(IOException.class, () -> initializationHooksRunner.run("startup", hookDirectory));

        Mockito.verify(hookScriptExecutorMock).run(hookDirectory, "10-bootstrap.sh");
        Assertions.assertSame(ioException, exception);
    }

    @Test
    @DisplayName("Should propagate InterruptedException from hook script executor")
    void shouldPropagateInterruptedExceptionFromHookScriptExecutor() throws IOException, InterruptedException {
        File hookDirectory = Mockito.mock(File.class);
        Mockito.when(hookDirectory.getAbsolutePath()).thenReturn("/hooks/startup");
        Mockito.when(hookDirectory.exists()).thenReturn(true);
        Mockito.when(hookDirectory.isDirectory()).thenReturn(true);
        Mockito.when(hookDirectory.list(ArgumentMatchers.any())).thenReturn(new String[]{"10-bootstrap.sh"});

        InterruptedException interruptedException = new InterruptedException("Hook execution interrupted");
        Mockito.doThrow(interruptedException).when(hookScriptExecutorMock).run(hookDirectory, "10-bootstrap.sh");

        InterruptedException exception = Assertions.assertThrows(InterruptedException.class, () -> initializationHooksRunner.run("startup", hookDirectory));

        Mockito.verify(hookScriptExecutorMock).run(hookDirectory, "10-bootstrap.sh");
        Assertions.assertSame(interruptedException, exception);
    }

}
