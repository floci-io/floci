package io.github.hectorvent.floci.services.lambda.durable.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** The last accepted checkpoint and its answer as sent, so a retry with the same ClientToken gets it again. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DurableCheckpointReplay {

    private String requestToken;
    private String clientToken;
    private String responseToken;
    private List<DurableOperation> operations = new ArrayList<>();

    public DurableCheckpointReplay() {
    }

    public DurableCheckpointReplay(String requestToken, String clientToken, String responseToken,
                                   List<DurableOperation> operations) {
        this.requestToken = requestToken;
        this.clientToken = clientToken;
        this.responseToken = responseToken;
        this.operations = new ArrayList<>();
        for (DurableOperation operation : operations) {
            this.operations.add(operation.copy());
        }
    }

    public String getRequestToken() { return requestToken; }
    public void setRequestToken(String requestToken) { this.requestToken = requestToken; }

    public String getClientToken() { return clientToken; }
    public void setClientToken(String clientToken) { this.clientToken = clientToken; }

    public String getResponseToken() { return responseToken; }
    public void setResponseToken(String responseToken) { this.responseToken = responseToken; }

    public List<DurableOperation> getOperations() { return operations; }
    public void setOperations(List<DurableOperation> operations) { this.operations = operations; }
}
