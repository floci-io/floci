package io.github.hectorvent.floci.services.lambda.launcher;

import io.github.hectorvent.floci.services.lambda.model.ContainerState;
import io.github.hectorvent.floci.services.lambda.runtime.RuntimeApiServer;

import java.io.Closeable;

/**
 * Wraps a running Lambda Docker container and its associated Runtime API server.
 */
public class ContainerHandle {

    private final String containerId;
    private final String functionName;
    private final RuntimeApiServer runtimeApiServer;
    private final long createdAt;
    private final boolean hotReload;
    private volatile ContainerState state;
    private volatile long lastUsedMs;
    private Closeable logStream;
    /**
     * The warm-pool generation this container was handed out under. {@code WarmPool} bumps a
     * function's generation whenever its containers are invalidated (delete, redeploy, code
     * update) and compares on release, so a container checked out across an invalidation is
     * discarded instead of being pooled again for the new code.
     */
    private volatile long poolGeneration;

    public ContainerHandle(String containerId, String functionName,
                           RuntimeApiServer runtimeApiServer, ContainerState state) {
        this(containerId, functionName, runtimeApiServer, state, false);
    }

    public ContainerHandle(String containerId, String functionName,
                           RuntimeApiServer runtimeApiServer, ContainerState state, boolean hotReload) {
        this.containerId = containerId;
        this.functionName = functionName;
        this.runtimeApiServer = runtimeApiServer;
        this.state = state;
        this.hotReload = hotReload;
        this.createdAt = System.currentTimeMillis();
        this.lastUsedMs = this.createdAt;
    }

    public String getContainerId() { return containerId; }
    public String getFunctionName() { return functionName; }
    public boolean isHotReload() { return hotReload; }
    public RuntimeApiServer getRuntimeApiServer() { return runtimeApiServer; }
    public long getCreatedAt() { return createdAt; }
    public long getLastUsedMs() { return lastUsedMs; }
    public void touchLastUsed() { this.lastUsedMs = System.currentTimeMillis(); }
    public ContainerState getState() { return state; }
    public void setState(ContainerState state) { this.state = state; }
    public Closeable getLogStream() { return logStream; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }
    public long getPoolGeneration() { return poolGeneration; }
    public void setPoolGeneration(long poolGeneration) { this.poolGeneration = poolGeneration; }
}
