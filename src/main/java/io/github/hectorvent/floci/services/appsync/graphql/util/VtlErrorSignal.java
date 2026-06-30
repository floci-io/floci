package io.github.hectorvent.floci.services.appsync.graphql.util;

public class VtlErrorSignal extends RuntimeException {

    private final String errorType;
    private final Object data;
    private final Object errorInfo;

    public VtlErrorSignal(String message, String errorType, Object data, Object errorInfo) {
        super(message);
        this.errorType = errorType;
        this.data = data;
        this.errorInfo = errorInfo;
    }

    public String getErrorType() {
        return errorType;
    }

    public Object getData() {
        return data;
    }

    public Object getErrorInfo() {
        return errorInfo;
    }
}
