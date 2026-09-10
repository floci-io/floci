package io.github.hectorvent.floci.lifecycle.inithook;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@ApplicationScoped
public class HookScriptExecutor {

    private static final Logger LOG = Logger.getLogger(HookScriptExecutor.class);
    private final EmulatorConfig.InitHooksConfig initHooksConfig;

    @Inject
    public HookScriptExecutor(final EmulatorConfig emulatorConfig) {
        this.initHooksConfig = emulatorConfig.initHooks();
    }

    public void run(final File scriptFile) throws IOException, InterruptedException {
        run(scriptFile.getParentFile(), scriptFile.getName());
    }

    public void run(final File hookDirectory, final String scriptFileName) throws IOException, InterruptedException {
        final String command = scriptFileName.endsWith(".py") ? "python3" : initHooksConfig.shellExecutable();
        LOG.debugv("Executing hook script {0} via {1}", scriptFileName, command);

        // Inherit parent I/O so script output is streamed directly and does not block on unconsumed buffers.
        final Process process = new ProcessBuilder(command, scriptFileName).directory(hookDirectory).inheritIO().start();
        run(process, scriptFileName);
    }

    void run(final Process process, final String scriptFileName) throws InterruptedException {
        final int exitCode = waitForProcessExitCode(process, scriptFileName);
        if (exitCode != 0) {
            final String message = String.format("Hook script failed: %s exited with code %d", scriptFileName, exitCode);
            throw new IllegalStateException(message);
        }
    }

    private int waitForProcessExitCode(final Process process, final String scriptFileName) throws InterruptedException {
        try {
            final long timeoutSeconds = initHooksConfig.timeoutSeconds();
            final boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                LOG.debugv("Hook script exceeded timeout of {0} seconds, terminating process: {1}", timeoutSeconds, scriptFileName);
                terminateProcess(process, scriptFileName);

                final String message = String.format("Hook script timed out after %d seconds: %s", timeoutSeconds, scriptFileName);
                throw new IllegalStateException(message);
            }

            return process.exitValue();
        } finally {
            if (process.isAlive()) {
                LOG.debugv("Hook script process still alive during cleanup, forcing termination: {0}", scriptFileName);
                forceTerminateProcessTree(process);
            }
        }
    }

    private void terminateProcess(final Process process, final String scriptFileName) throws InterruptedException {
        // Try a graceful shutdown first, then force termination if the process does not exit in time.
        List<ProcessHandle> descendants = processDescendants(process);
        descendants.forEach(ProcessHandle::destroy);
        process.destroy();
        final long shutdownGracePeriodSeconds = initHooksConfig.shutdownGracePeriodSeconds();
        final long gracefulDeadline = deadlineNanos(shutdownGracePeriodSeconds);
        final boolean terminatedGracefully = waitForProcess(process, gracefulDeadline);
        final boolean descendantsTerminated = waitForDescendants(descendants, gracefulDeadline);
        if (!terminatedGracefully || !descendantsTerminated) {
            LOG.debugv("Hook script process tree did not terminate gracefully, forcing termination: {0}",
                    scriptFileName);
            process.destroyForcibly();
            List<ProcessHandle> remainingDescendants = new ArrayList<>(descendants);
            remainingDescendants.addAll(processDescendants(process));
            remainingDescendants.forEach(ProcessHandle::destroyForcibly);
            final long forceDeadline = deadlineNanos(shutdownGracePeriodSeconds);
            waitForProcess(process, forceDeadline);
            waitForDescendants(remainingDescendants, forceDeadline);
        }
    }

    private List<ProcessHandle> processDescendants(final Process process) {
        ProcessHandle handle = process.toHandle();
        return handle == null ? List.of() : handle.descendants().toList();
    }

    private boolean waitForDescendants(List<ProcessHandle> descendants, long deadlineNanos)
            throws InterruptedException {
        for (ProcessHandle descendant : descendants) {
            if (!waitForExit(descendant, deadlineNanos)) {
                return false;
            }
        }
        return true;
    }

    private boolean waitForExit(ProcessHandle process, long deadlineNanos) throws InterruptedException {
        if (!process.isAlive()) {
            return true;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return false;
        }
        try {
            process.onExit().get(remainingNanos, TimeUnit.NANOSECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (ExecutionException e) {
            return !process.isAlive();
        }
    }

    private boolean waitForProcess(Process process, long deadlineNanos) throws InterruptedException {
        if (!process.isAlive()) {
            return true;
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos > 0 && process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
    }

    private long deadlineNanos(long timeoutSeconds) {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
    }

    private void forceTerminateProcessTree(Process process) {
        final long shutdownGracePeriodSeconds = initHooksConfig.shutdownGracePeriodSeconds();
        final long deadline = deadlineNanos(shutdownGracePeriodSeconds);
        List<ProcessHandle> descendants = new ArrayList<>(processDescendants(process));
        process.destroyForcibly();
        descendants.addAll(processDescendants(process));
        descendants.forEach(ProcessHandle::destroyForcibly);
        try {
            waitForProcess(process, deadline);
            waitForDescendants(descendants, deadline);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
