package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class HistoryEvent {
    private long id;
    private double timestamp;
    private String type;
    private Long previousEventId;
    private Map<String, Object> details;

    public HistoryEvent() {
        this.timestamp = System.currentTimeMillis() / 1000.0;
    }

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public double getTimestamp() { return timestamp; }
    public void setTimestamp(double timestamp) { this.timestamp = timestamp; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Long getPreviousEventId() { return previousEventId; }
    public void setPreviousEventId(Long previousEventId) { this.previousEventId = previousEventId; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
