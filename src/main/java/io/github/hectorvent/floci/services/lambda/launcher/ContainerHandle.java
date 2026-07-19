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
    private final String executionRoleAccessKeyId;
    private final String executionRoleSessionAccountId;
    private volatile ContainerState state;
    private volatile long lastUsedMs;
    private Closeable logStream;

    public ContainerHandle(String containerId, String functionName,
                           RuntimeApiServer runtimeApiServer, ContainerState state) {
        this(containerId, functionName, runtimeApiServer, state, false);
    }

    public ContainerHandle(String containerId, String functionName,
                           RuntimeApiServer runtimeApiServer, ContainerState state, boolean hotReload) {
        this(containerId, functionName, runtimeApiServer, state, hotReload, null, null);
    }

    public ContainerHandle(String containerId, String functionName,
                           RuntimeApiServer runtimeApiServer, ContainerState state, boolean hotReload,
                           String executionRoleAccessKeyId, String executionRoleSessionAccountId) {
        this.containerId = containerId;
        this.functionName = functionName;
        this.runtimeApiServer = runtimeApiServer;
        this.state = state;
        this.hotReload = hotReload;
        this.executionRoleAccessKeyId = executionRoleAccessKeyId;
        this.executionRoleSessionAccountId = executionRoleSessionAccountId;
        this.createdAt = System.currentTimeMillis();
        this.lastUsedMs = this.createdAt;
    }

    public String getContainerId() { return containerId; }
    public String getFunctionName() { return functionName; }
    public boolean isHotReload() { return hotReload; }
    public String getExecutionRoleAccessKeyId() { return executionRoleAccessKeyId; }
    public String getExecutionRoleSessionAccountId() { return executionRoleSessionAccountId; }
    public RuntimeApiServer getRuntimeApiServer() { return runtimeApiServer; }
    public long getCreatedAt() { return createdAt; }
    public long getLastUsedMs() { return lastUsedMs; }
    public void touchLastUsed() { this.lastUsedMs = System.currentTimeMillis(); }
    public ContainerState getState() { return state; }
    public void setState(ContainerState state) { this.state = state; }
    public Closeable getLogStream() { return logStream; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }
}
