package io.github.hectorvent.floci.lifecycle.inithook;

import io.github.hectorvent.floci.config.EmulatorConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class HookScriptExecutor {

    private static final Logger LOG = Logger.getLogger(HookScriptExecutor.class);
    private final EmulatorConfig.InitHooksConfig initHooksConfig;

    @Inject
    public HookScriptExecutor(final EmulatorConfig emulatorConfig) {
        this.initHooksConfig = emulatorConfig.initHooks();
    }

    public void run(final File hookDirectory, final String scriptFileName) throws IOException, InterruptedException {
        final String shellExecutable = initHooksConfig.shellExecutable();
        LOG.debugv("Executing hook script {0} via {1}", scriptFileName, shellExecutable);

        // Inherit parent I/O so script output is streamed directly and does not block on unconsumed buffers.
        final Process process = new ProcessBuilder(shellExecutable, scriptFileName).directory(hookDirectory).inheritIO().start();
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
                process.destroyForcibly();
            }
        }
    }

    private void terminateProcess(final Process process, final String scriptFileName) throws InterruptedException {
        // Try a graceful shutdown first, then force termination if the process does not exit in time.
        process.destroy();
        if (process.isAlive()) {
            final long shutdownGracePeriodSeconds = initHooksConfig.shutdownGracePeriodSeconds();
            final boolean terminatedGracefully = process.waitFor(shutdownGracePeriodSeconds, TimeUnit.SECONDS);
            if (!terminatedGracefully) {
                LOG.debugv("Hook script process did not terminate gracefully, forcing termination: {0}", scriptFileName);
                process.destroyForcibly();
                process.waitFor(shutdownGracePeriodSeconds, TimeUnit.SECONDS);
            }
        }
    }

}
