package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * One part of an event payload. AWS models this as a union of {@code conversational} and
 * {@code blob}; exactly one is set, and the other stays absent from the wire.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PayloadType {
    @JsonProperty("conversational")
    private Conversational conversational;
    @JsonProperty("blob")
    private Object blob;

    public PayloadType() {}

    public Conversational getConversational() { return conversational; }
    public void setConversational(Conversational conversational) { this.conversational = conversational; }
    public Object getBlob() { return blob; }
    public void setBlob(Object blob) { this.blob = blob; }
}
