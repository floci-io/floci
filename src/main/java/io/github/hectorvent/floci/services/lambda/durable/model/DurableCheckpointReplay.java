package io.github.hectorvent.floci.services.lambda.durable.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** The last accepted checkpoint, kept so a retry with the same ClientToken gets the same answer. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DurableCheckpointReplay {

    private String requestToken;
    private String clientToken;
    private String responseToken;
    private List<String> operationIds = new ArrayList<>();

    public DurableCheckpointReplay() {
    }

    public DurableCheckpointReplay(String requestToken, String clientToken, String responseToken,
                                   List<String> operationIds) {
        this.requestToken = requestToken;
        this.clientToken = clientToken;
        this.responseToken = responseToken;
        this.operationIds = new ArrayList<>(operationIds);
    }

    public String getRequestToken() { return requestToken; }
    public void setRequestToken(String requestToken) { this.requestToken = requestToken; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getResponseToken() { return responseToken; }
    public void setResponseToken(String responseToken) { this.responseToken = responseToken; }

    public List<String> getOperationIds() { return operationIds; }
    public void setOperationIds(List<String> operationIds) { this.operationIds = operationIds; }
}
