package io.github.hectorvent.floci.lifecycle.inithook;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.lifecycle.InitLifecycleState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class InitializationHooksRunner {

    private static final Logger LOG = Logger.getLogger(InitializationHooksRunner.class);

    private final HookScriptExecutor hookScriptExecutor;
    private final InitLifecycleState initLifecycleState;
    private final String hooksRoot;
    private final FilenameFilter scriptFileFilter;
    private final Set<String> warnedSkips = ConcurrentHashMap.newKeySet();

    @Inject
    public InitializationHooksRunner(final HookScriptExecutor hookScriptExecutor,
                                     final InitLifecycleState initLifecycleState,
                                     final EmulatorConfig emulatorConfig) {
        this(hookScriptExecutor, initLifecycleState, emulatorConfig, System.getProperty("os.name", ""));
    }

    InitializationHooksRunner(final HookScriptExecutor hookScriptExecutor,
                              final InitLifecycleState initLifecycleState,
                              final EmulatorConfig emulatorConfig,
                              final String osName) {
        this.hookScriptExecutor = hookScriptExecutor;
        this.initLifecycleState = initLifecycleState;
        this.hooksRoot = emulatorConfig.initHooks().dir();
        boolean windows = HookScripts.isWindows(osName);
        String shell = emulatorConfig.initHooks().shellExecutable();
        this.scriptFileFilter = (dir, name) -> runnable(dir, name, windows, shell);
    }

    /**
     * Whether a file in a hook directory runs here. Windows script types are ignored without a word
     * on Linux and macOS; a {@code .sh} hook Windows cannot run is warned about once per file, not
     * on every directory listing.
     */
    private boolean runnable(File dir, String name, boolean windows, String shell) {
        if (!HookScripts.isScript(name, windows)) {
            return false;
        }
        if (HookScripts.canRun(name, windows, shell)) {
            return true;
        }
        if (windows && warnedSkips.add(new File(dir, name).getAbsolutePath())) {
            LOG.warnv("Skipping hook script {0}: it cannot run on this platform (shell {1})", name, shell);
        }
        return false;
    }

    public boolean hasHooks(final InitializationHook hook) {
        return !findMergedScripts(hook).isEmpty();
    }

    public void run(final InitializationHook hook) throws IOException, InterruptedException {
        final List<File> scripts = findMergedScripts(hook);
        if (!scripts.isEmpty()) {
            LOG.infov("Running {0} hook with {1} script(s): {2}",
                    hook.getName(), scripts.size(),
                    scripts.stream().map(File::getAbsolutePath).toList());
            for (final File script : scripts) {
                LOG.infov("Executing {0} hook script: {1}", hook.getName(), script.getAbsolutePath());
                try {
                    hookScriptExecutor.run(script);
                    initLifecycleState.addScript(hook, script.getAbsolutePath(), "successful", 0);
                } catch (IllegalStateException e) {
                    initLifecycleState.addScript(hook, script.getAbsolutePath(), "error", parseExitCode(e));
                    throw e;
                }
            }
        }
    }

    private static int parseExitCode(IllegalStateException e) {
        String msg = e.getMessage();
        if (msg != null && msg.contains("exited with code ")) {
            try {
                return Integer.parseInt(msg.substring(msg.lastIndexOf(' ') + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    /**
     * Runs scripts from an arbitrary directory — kept for test utilities and direct invocations.
     */
    public void run(final String hookName, final File hookDirectory) throws IOException, InterruptedException {
        final String[] scriptFileNames = findScriptFileNames(hookName, hookDirectory);
        if (scriptFileNames.length > 0) {
            LOG.infov("Running {0} hook with {1} script(s) from {2}: {3}",
                    hookName, scriptFileNames.length, hookDirectory.getAbsolutePath(),
                    Arrays.toString(scriptFileNames));
            for (final String scriptFileName : scriptFileNames) {
                LOG.infov("Executing {0} hook script: {1}", hookName, scriptFileName);
                hookScriptExecutor.run(hookDirectory, scriptFileName);
            }
        }
    }

    /**
     * Merges scripts from all primary (Floci) paths and then compat (LocalStack) paths.
     * First occurrence of a filename wins; merged list is sorted lexicographically.
     */
    private List<File> findMergedScripts(final InitializationHook hook) {
        final Map<String, File> merged = new LinkedHashMap<>();
        for (final File dir : hook.getPrimaryPaths(hooksRoot)) {
            collectScripts(hook.getName(), dir, merged);
        }
        for (final File dir : hook.getCompatPaths()) {
            collectScripts(hook.getName(), dir, merged);
        }
        return merged.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(Map.Entry::getValue)
                .toList();
    }

    private void collectScripts(final String hookName, final File dir,
                                       final Map<String, File> target) {
        if (!dir.exists()) {
            LOG.debugv("{0} hook directory does not exist: {1}", hookName, dir.getAbsolutePath());
            return;
        }
        if (!dir.isDirectory()) {
            LOG.warnv("{0} hook path is not a directory: {1}", hookName, dir.getAbsolutePath());
            return;
        }
        final File[] scripts = dir.listFiles(scriptFileFilter);
        if (scripts == null || scripts.length == 0) {
            LOG.debugv("No {0} hook scripts found in {1}", hookName, dir.getAbsolutePath());
            return;
        }
        for (final File script : scripts) {
            if (target.putIfAbsent(script.getName(), script) == null) {
                LOG.debugv("Found {0} hook script: {1}", hookName, script.getAbsolutePath());
            } else {
                LOG.debugv("Skipping {0} (shadowed by higher-priority path)", script.getAbsolutePath());
            }
        }
    }

    private String[] findScriptFileNames(final String hookName, final File hookDirectory) {
        if (!hookDirectory.exists()) {
            LOG.debugv("{0} hook directory does not exist: {1}", hookName, hookDirectory.getAbsolutePath());
            return new String[0];
        }
        if (!hookDirectory.isDirectory()) {
            LOG.warnv("{0} hook path is not a directory: {1}", hookName, hookDirectory.getAbsolutePath());
            return new String[0];
        }
        final String[] scriptFileNames = hookDirectory.list(scriptFileFilter);
        if (scriptFileNames == null || scriptFileNames.length == 0) {
            LOG.debugv("No {0} hook scripts found in {1}", hookName, hookDirectory.getAbsolutePath());
            return new String[0];
        }
        Arrays.sort(scriptFileNames);
        return scriptFileNames;
    }
}
