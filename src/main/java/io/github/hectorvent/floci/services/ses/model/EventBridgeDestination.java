package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventBridgeDestination {

    @JsonProperty("EventBusArn")
    private String eventBusArn;

    public EventBridgeDestination() {}

    public String getEventBusArn() { return eventBusArn; }
    public void setEventBusArn(String eventBusArn) { this.eventBusArn = eventBusArn; }
}
