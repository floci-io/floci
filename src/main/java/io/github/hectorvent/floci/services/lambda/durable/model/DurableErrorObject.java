package io.github.hectorvent.floci.services.lambda.durable.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** The ErrorObject shape shared by checkpoint updates, execution results and history events. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DurableErrorObject {

    private String errorMessage;
    private String errorType;
    private String errorData;
    private List<String> stackTrace;

    public DurableErrorObject() {
    }

    public static DurableErrorObject of(String errorMessage, String errorType) {
        DurableErrorObject error = new DurableErrorObject();
        error.setErrorMessage(errorMessage);
        error.setErrorType(errorType);
        return error;
    }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getErrorType() { return errorType; }
    public void setErrorType(String errorType) { this.errorType = errorType; }

    public String getErrorData() { return errorData; }
    public void setErrorData(String errorData) { this.errorData = errorData; }

    public List<String> getStackTrace() { return stackTrace; }
    public void setStackTrace(List<String> stackTrace) { this.stackTrace = stackTrace; }
}
