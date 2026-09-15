package io.github.hectorvent.floci.services.bedrockagentcore.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/**
 * One AgentCore Memory event.
 *
 * <p>Named {@code MemoryEvent} rather than {@code Event} to keep it distinct from the many other
 * event types in this codebase. The wire name is {@code Event}.
 *
 * <p>{@code eventTimestamp} is carried as epoch milliseconds and serialised as epoch seconds with a
 * fractional part, which is the restJson1 default the SDK expects.
 */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MemoryEvent {
    @JsonProperty("memoryId")
    private String memoryId;
    @JsonProperty("actorId")
    private String actorId;
    @JsonProperty("sessionId")
    private String sessionId;
    @JsonProperty("eventId")
    private String eventId;
    @JsonProperty("eventTimestamp")
    private Double eventTimestamp;
    @JsonProperty("payload")
    private List<PayloadType> payload;
    @JsonProperty("branch")
    private Branch branch;

    public MemoryEvent() {}

    public String getMemoryId() { return memoryId; }
    public void setMemoryId(String memoryId) { this.memoryId = memoryId; }
    public String getActorId() { return actorId; }
    public void setActorId(String actorId) { this.actorId = actorId; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public Double getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(Double eventTimestamp) { this.eventTimestamp = eventTimestamp; }
    public List<PayloadType> getPayload() { return payload == null ? null : new ArrayList<>(payload); }
    public void setPayload(List<PayloadType> payload) { this.payload = payload == null ? null : new ArrayList<>(payload); }
    public Branch getBranch() { return branch; }
    public void setBranch(Branch branch) { this.branch = branch; }
}
