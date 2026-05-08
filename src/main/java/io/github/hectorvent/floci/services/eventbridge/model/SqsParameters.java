package io.github.hectorvent.floci.services.eventbridge.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SqsParameters {

    private String messageGroupId;

    public SqsParameters() {}

    public String getMessageGroupId() { return messageGroupId; }
    public void setMessageGroupId(String messageGroupId) { this.messageGroupId = messageGroupId; }
}
