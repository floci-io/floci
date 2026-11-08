package io.floci.aws.lifecycle;

import io.floci.aws.lifecycle.inithook.InitializationHook;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class InitLifecycleState {

    public record ScriptRecord(String script, String state, int returnCode) {}

    private volatile boolean bootCompleted = false;
    private volatile boolean startCompleted = false;
    private volatile boolean readyCompleted = false;
    private volatile boolean shutdownStarted = false;

    private final Map<InitializationHook, List<ScriptRecord>> scriptsByPhase = new EnumMap<>(InitializationHook.class);

    public void markBootCompleted() { bootCompleted = true; }
    public void markStartCompleted() { startCompleted = true; }
    public void markReadyCompleted() { readyCompleted = true; }
    public void markShutdownStarted() { shutdownStarted = true; }

    public boolean isBootCompleted() { return bootCompleted; }
    public boolean isStartCompleted() { return startCompleted; }
    public boolean isReadyCompleted() { return readyCompleted; }
    public boolean isShutdownStarted() { return shutdownStarted; }

    public synchronized void addScript(InitializationHook hook, String scriptPath, String state, int returnCode) {
        scriptsByPhase.computeIfAbsent(hook, k -> new ArrayList<>()).add(new ScriptRecord(scriptPath, state, returnCode));
    }

    public synchronized List<ScriptRecord> getScripts(InitializationHook hook) {
        return List.copyOf(scriptsByPhase.getOrDefault(hook, List.of()));
    }
}
