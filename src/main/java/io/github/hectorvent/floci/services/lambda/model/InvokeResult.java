package io.github.hectorvent.floci.services.lambda.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class InvokeResult {

    private int statusCode;
    private byte[] payload;
    private String functionError;
    private String logResult;
    private String requestId;
    private String executedVersion;

    public InvokeResult() {
    }

    public InvokeResult(int statusCode, String functionError, byte[] payload, String logResult, String requestId) {
        this.statusCode = statusCode;
        this.functionError = functionError;
        this.payload = payload;
        this.logResult = logResult;
        this.requestId = requestId;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public void setStatusCode(int statusCode) {
        this.statusCode = statusCode;
    }

    public String getFunctionError() {
        return functionError;
    }

    public void setFunctionError(String functionError) {
        this.functionError = functionError;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String getLogResult() {
        return logResult;
    }

    public void setLogResult(String logResult) {
        this.logResult = logResult;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getExecutedVersion() {
        return executedVersion;
    }

    public void setExecutedVersion(String executedVersion) {
        this.executedVersion = executedVersion;
    }
}
