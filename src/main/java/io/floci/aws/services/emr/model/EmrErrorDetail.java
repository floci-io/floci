package io.floci.aws.services.emr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One of a cluster's ErrorDetails, "A tuple that provides information about an error that caused a cluster to
 * terminate": its ErrorCode, its ErrorData, a list of key-value maps, and its ErrorMessage.
 */
@RegisterForReflection
public class EmrErrorDetail {

    private String errorCode;
    private List<Map<String, String>> errorData = new ArrayList<>();
    private String errorMessage;

    public EmrErrorDetail() {}

    public EmrErrorDetail(String errorCode, List<Map<String, String>> errorData, String errorMessage) {
        this.errorCode = errorCode;
        this.errorData = errorData;
        this.errorMessage = errorMessage;
    }

    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }

    public List<Map<String, String>> getErrorData() { return errorData; }
    public void setErrorData(List<Map<String, String>> errorData) { this.errorData = errorData; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
