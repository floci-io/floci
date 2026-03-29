package io.github.hectorvent.floci.lifecycle.inithook;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;

@ApplicationScoped
public class InitializationHooksRunner {

    private static final Logger LOG = Logger.getLogger(InitializationHooksRunner.class);

    private static final String SHELL_SCRIPT_EXTENSION = ".sh";
    private static final FilenameFilter FILE_EXTENSION_FILTER = (ignored, name) -> name.endsWith(SHELL_SCRIPT_EXTENSION);

    private final HookScriptExecutor hookScriptExecutor;

    @Inject
    public InitializationHooksRunner(final HookScriptExecutor hookScriptExecutor) {
        this.hookScriptExecutor = hookScriptExecutor;
    }

    private static String[] findScriptFileNames(final String hookName, final File hookDirectory) {
        final String hookDirectoryPath = hookDirectory.getAbsolutePath();
        if (!hookDirectory.exists()) {
            LOG.debugv("{0} hook directory does not exist: {1}", hookName, hookDirectoryPath);
            return new String[0];
        } else if (!hookDirectory.isDirectory()) {
            LOG.warnv("{0} hook path is not a directory: {1}", hookName, hookDirectoryPath);
            return new String[0];
        }

        final String[] scriptFileNames = hookDirectory.list(FILE_EXTENSION_FILTER);
        if ((scriptFileNames == null) || (scriptFileNames.length == 0)) {
            LOG.debugv("No {0} hook scripts found in {1}", hookName, hookDirectoryPath);
            return new String[0];
        }

        Arrays.sort(scriptFileNames);
        return scriptFileNames;
    }

    public void run(final InitializationHook hook) throws IOException, InterruptedException {
        final String hookName = hook.getName();
        final File hookDirectory = hook.getPath();
        run(hookName, hookDirectory);
    }

    public void run(final String hookName, final File hookDirectory) throws IOException, InterruptedException {
        final String[] scriptFileNames = findScriptFileNames(hookName, hookDirectory);
        if (scriptFileNames.length > 0) {
            final String hookDirectoryPath = hookDirectory.getAbsolutePath();
            final String scriptList = Arrays.toString(scriptFileNames);
            LOG.infov("Running {0} hook with {1} script(s) from {2}: {3}", hookName, scriptFileNames.length, hookDirectoryPath, scriptList);

            // Hook scripts are executed sequentially and treated as dependent steps.
            // Stop at the first script failure to avoid continuing in a partially initialized state
            for (var scriptFileName : scriptFileNames) {
                LOG.infov("Executing {0} hook script: {1}", hookName, scriptFileName);
                hookScriptExecutor.run(hookDirectory, scriptFileName);
            }
        }
    }
}
