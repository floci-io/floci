package io.floci.aws.services.lambda.launcher;

import io.floci.aws.services.lambda.model.ContainerState;
import io.floci.aws.services.lambda.runtime.RuntimeApiServer;

import java.io.Closeable;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

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
    private Function<Instant, String> logTailReader = since -> "";
    private final AtomicBoolean invoked = new AtomicBoolean();

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
    public void touchLastUsed(long nowMs) { this.lastUsedMs = nowMs; }
    public ContainerState getState() { return state; }
    public void setState(ContainerState state) { this.state = state; }

    public Closeable getLogStream() { return logStream; }
    public void setLogStream(Closeable logStream) { this.logStream = logStream; }

    public void setLogTailReader(Function<Instant, String> reader) { this.logTailReader = reader; }
    public String readLogTail(Instant since) { return logTailReader.apply(since); }

    /** The first invocation also owns the environment's initialization logs. */
    public Instant beginInvocation() {
        return invoked.getAndSet(true) ? Instant.now() : Instant.EPOCH;
    }

    /**
     * True once an extension in this container reported an init or exit error. Real AWS treats
     * both as fatal to the execution environment, so {@code WarmPool} must retire the container
     * instead of pooling or reusing it. Read from the container's {@code RuntimeApiServer}, which
     * is where extensions report the error; a handle with no runtime server (test fixtures) is
     * never faulted.
     */
    public boolean isFaulted() {
        return runtimeApiServer != null && runtimeApiServer.isFaulted();
    }
}
