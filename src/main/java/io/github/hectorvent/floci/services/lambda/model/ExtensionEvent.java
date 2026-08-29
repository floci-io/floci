package io.github.hectorvent.floci.services.lambda.model;

/**
 * A lifecycle event delivered to a registered extension via
 * {@code GET /2020-01-01/extension/event/next} — either {@code INVOKE} (mirrors a runtime
 * invocation) or {@code SHUTDOWN} (container is about to stop).
 */
public class ExtensionEvent {

    public enum Type { INVOKE, SHUTDOWN }

    private final Type type;
    private final String requestId;
    private final long deadlineMs;
    private final String functionArn;
    private final String shutdownReason;

    public static ExtensionEvent invoke(String requestId, long deadlineMs, String functionArn) {
        return new ExtensionEvent(Type.INVOKE, requestId, deadlineMs, functionArn, null);
    }

    public static ExtensionEvent shutdown(long deadlineMs, String shutdownReason) {
        return new ExtensionEvent(Type.SHUTDOWN, null, deadlineMs, null, shutdownReason);
    }

    private ExtensionEvent(Type type, String requestId, long deadlineMs, String functionArn, String shutdownReason) {
        this.type = type;
        this.requestId = requestId;
        this.deadlineMs = deadlineMs;
        this.functionArn = functionArn;
        this.shutdownReason = shutdownReason;
    }

    public Type getType() { return type; }
    public String getRequestId() { return requestId; }
    public long getDeadlineMs() { return deadlineMs; }
    public String getFunctionArn() { return functionArn; }
    public String getShutdownReason() { return shutdownReason; }
}
