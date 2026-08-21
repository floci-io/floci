package io.github.hectorvent.floci.services.fis.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class IdempotencyRecord {
    private ObjectNode request;
    private ObjectNode response;

    public IdempotencyRecord() {
    }

    public IdempotencyRecord(ObjectNode request, ObjectNode response) {
        this.request = request;
        this.response = response;
    }

    public ObjectNode getRequest() {
        return request;
    }

    public void setRequest(ObjectNode request) {
        this.request = request;
    }

    public ObjectNode getResponse() {
        return response;
    }

    public void setResponse(ObjectNode response) {
        this.response = response;
    }
}
