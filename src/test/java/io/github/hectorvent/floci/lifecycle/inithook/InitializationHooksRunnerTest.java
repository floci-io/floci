package io.github.hectorvent.floci.lifecycle.inithook;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.lifecycle.InitLifecycleState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.github.hectorvent.floci.testing.InlineMocks.inlineMock;

@ExtendWith(MockitoExtension.class)
class InitializationHooksRunnerTest {

    @Mock
    private HookScriptExecutor hookScriptExecutorMock;

    @Mock
    private InitLifecycleState initLifecycleStateMock;

    private InitializationHooksRunner initializationHooksRunner;

    @BeforeEach
    void setUp() {
        initializationHooksRunner = runner(InitializationHook.DEFAULT_ROOT, "Linux");
    }

    private InitializationHooksRunner runner(String root, String osName) {
        return runner(root, osName, "/bin/sh");
    }

    private InitializationHooksRunner runner(String root, String osName, String shell) {
        EmulatorConfig config = Mockito.mock(EmulatorConfig.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.lenient().when(config.initHooks().dir()).thenReturn(root);
        Mockito.lenient().when(config.initHooks().shellExecutable()).thenReturn(shell);
        return new InitializationHooksRunner(hookScriptExecutorMock, initLifecycleStateMock, config, osName);
    }

    @Test
    void configuredDirReplacesDefaultRoot(@TempDir Path root) throws IOException, InterruptedException {
        Files.createDirectories(root.resolve("ready.d"));
        Files.createFile(root.resolve("ready.d").resolve("01-seed.sh"));

        runner(root.toString(), "Linux").run(InitializationHook.READY);

        Mockito.verify(hookScriptExecutorMock).run(root.resolve("ready.d").resolve("01-seed.sh").toFile());
    }

    @Test
    void configuredDirKeepsBothStopDirectories(@TempDir Path root) throws IOException, InterruptedException {
        Files.createDirectories(root.resolve("stop.d"));
        Files.createDirectories(root.resolve("shutdown.d"));
        Files.createFile(root.resolve("stop.d").resolve("01-a.sh"));
        Files.createFile(root.resolve("shutdown.d").resolve("02-b.sh"));

        runner(root.toString(), "Linux").run(InitializationHook.STOP);

        InOrder inOrder = Mockito.inOrder(hookScriptExecutorMock);
        inOrder.verify(hookScriptExecutorMock).run(root.resolve("stop.d").resolve("01-a.sh").toFile());
        inOrder.verify(hookScriptExecutorMock).run(root.resolve("shutdown.d").resolve("02-b.sh").toFile());
    }

    @Test
    void windowsRunsNativeScriptsAndSkipsShell(@TempDir Path root) throws IOException, InterruptedException {
        Path ready = Files.createDirectories(root.resolve("ready.d"));
        Files.createFile(ready.resolve("01-seed.ps1"));
        Files.createFile(ready.resolve("02-seed.cmd"));
        Files.createFile(ready.resolve("03-seed.sh"));

        runner(root.toString(), "Windows 11").run(InitializationHook.READY);

        Mockito.verify(hookScriptExecutorMock).run(ready.resolve("01-seed.ps1").toFile());
        Mockito.verify(hookScriptExecutorMock).run(ready.resolve("02-seed.cmd").toFile());
        Mockito.verify(hookScriptExecutorMock, Mockito.never()).run(ready.resolve("03-seed.sh").toFile());
    }

    @Test
    void windowsRunsUppercaseExtensions(@TempDir Path root) throws IOException, InterruptedException {
        Path ready = Files.createDirectories(root.resolve("ready.d"));
        Files.createFile(ready.resolve("01-SETUP.BAT"));
        Files.createFile(ready.resolve("02-Init.PS1"));

        runner(root.toString(), "Windows 11").run(InitializationHook.READY);

        Mockito.verify(hookScriptExecutorMock).run(ready.resolve("01-SETUP.BAT").toFile());
        Mockito.verify(hookScriptExecutorMock).run(ready.resolve("02-Init.PS1").toFile());
    }

    @Test
    void windowsRunsShellScriptsWithAConfiguredShell(@TempDir Path root) throws IOException, InterruptedException {
        Path ready = Files.createDirectories(root.resolve("ready.d"));
        Files.createFile(ready.resolve("01-seed.sh"));

        runner(root.toString(), "Windows 11", "C:/Program Files/Git/bin/bash.exe").run(InitializationHook.READY);

        Mockito.verify(hookScriptExecutorMock).run(ready.resolve("01-seed.sh").toFile());
    }

    @Test
    void unixIgnoresWindowsScripts(@TempDir Path root) throws IOException, InterruptedException {
        Path ready = Files.createDirectories(root.resolve("ready.d"));
        Files.createFile(ready.resolve("01-seed.ps1"));

        runner(root.toString(), "Linux").run(InitializationHook.READY);

        Mockito.verifyNoInteractions(hookScriptExecutorMock);
    }

    @Test
    @DisplayName("Should execute shell scripts in sorted order")
    void shouldExecuteShellScriptsInSortedOrder() throws IOException, InterruptedException {
        File hookDirectory = Mockito.mock(File.class);
        Mockito.when(hookDirectory.getAbsolutePath()).thenReturn("/hooks/startup");
        Mockito.when(hookDirectory.exists()).thenReturn(true);
        Mockito.when(hookDirectory.isDirectory()).thenReturn(true);
        Mockito.when(hookDirectory.list(ArgumentMatchers.any())).thenReturn(new String[]{"20-third.sh", "10-first.sh", "15-second.sh"});

        initializationHooksRunner.run("startup", hookDirectory);

        InOrder inOrder = Mockito.inOrder(hookScriptExecutorMock);
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

        InOrder inOrder = Mockito.inOrder(hookScriptExecutorMock);
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

    @Test
    @DisplayName("hasHooks should return true when scripts exist in a primary directory")
    void hasHooksShouldReturnTrueWhenScriptsExist(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("01-setup.sh"));
        InitializationHook hook = inlineMock(InitializationHook.class);
        Mockito.when(hook.getName()).thenReturn("startup");
        Mockito.when(hook.getPrimaryPaths(Mockito.anyString())).thenReturn(List.of(tempDir.toFile()));
        Mockito.when(hook.getCompatPaths()).thenReturn(List.of());

        Assertions.assertTrue(initializationHooksRunner.hasHooks(hook));
    }

    @Test
    @DisplayName("hasHooks should return true when scripts exist only in a compat directory")
    void hasHooksShouldReturnTrueWhenScriptsExistInCompatDir(@TempDir Path tempDir) throws IOException {
        Files.createFile(tempDir.resolve("01-setup.sh"));
        InitializationHook hook = inlineMock(InitializationHook.class);
        Mockito.when(hook.getName()).thenReturn("startup");
        Mockito.when(hook.getPrimaryPaths(Mockito.anyString())).thenReturn(List.of());
        Mockito.when(hook.getCompatPaths()).thenReturn(List.of(tempDir.toFile()));

        Assertions.assertTrue(initializationHooksRunner.hasHooks(hook));
    }

    @Test
    @DisplayName("hasHooks should return false when all hook directories are empty")
    void hasHooksShouldReturnFalseWhenDirectoryIsEmpty(@TempDir Path tempDir) {
        InitializationHook hook = inlineMock(InitializationHook.class);
        Mockito.when(hook.getName()).thenReturn("startup");
        Mockito.when(hook.getPrimaryPaths(Mockito.anyString())).thenReturn(List.of(tempDir.toFile()));
        Mockito.when(hook.getCompatPaths()).thenReturn(List.of());

        Assertions.assertFalse(initializationHooksRunner.hasHooks(hook));
    }

    @Test
    @DisplayName("hasHooks should return false when hook directories do not exist")
    void hasHooksShouldReturnFalseWhenDirectoryDoesNotExist() {
        InitializationHook hook = inlineMock(InitializationHook.class);
        Mockito.when(hook.getName()).thenReturn("startup");
        Mockito.when(hook.getPrimaryPaths(Mockito.anyString())).thenReturn(List.of(new File("/nonexistent/path")));
        Mockito.when(hook.getCompatPaths()).thenReturn(List.of());

        Assertions.assertFalse(initializationHooksRunner.hasHooks(hook));
    }

    @Test
    @DisplayName("Primary path script should shadow same-named compat path script")
    void primaryPathShouldShadowCompatPathForSameFilename(@TempDir Path primaryDir, @TempDir Path compatDir)
            throws IOException, InterruptedException {
        Files.createFile(primaryDir.resolve("01-seed.sh"));
        Files.createFile(compatDir.resolve("01-seed.sh"));
        Files.createFile(compatDir.resolve("02-extra.sh"));

        InitializationHook hook = inlineMock(InitializationHook.class);
        Mockito.when(hook.getName()).thenReturn("ready");
        Mockito.when(hook.getPrimaryPaths(Mockito.anyString())).thenReturn(List.of(primaryDir.toFile()));
        Mockito.when(hook.getCompatPaths()).thenReturn(List.of(compatDir.toFile()));

        initializationHooksRunner.run(hook);

        // 01-seed.sh from primaryDir wins; 02-extra.sh from compatDir is included
        InOrder inOrder = Mockito.inOrder(hookScriptExecutorMock);
        inOrder.verify(hookScriptExecutorMock).run(primaryDir.resolve("01-seed.sh").toFile());
        inOrder.verify(hookScriptExecutorMock).run(compatDir.resolve("02-extra.sh").toFile());
        inOrder.verifyNoMoreInteractions();
    }

}
