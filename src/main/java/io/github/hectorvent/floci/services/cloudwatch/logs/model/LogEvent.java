package io.github.hectorvent.floci.services.cloudwatch.logs.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogEvent {

    private String eventId;
    private long timestamp;
    private String message;
    private long ingestionTime;
    /**
     * Monotonically increasing ingestion sequence number, used to preserve ingestion
     * order as a tie-breaker when multiple events share the same millisecond timestamp.
     * Matches CloudWatch Logs, which returns same-timestamp events in ingestion order.
     */
    private long sequence;

    public LogEvent() {}

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public long getIngestionTime() { return ingestionTime; }
    public void setIngestionTime(long ingestionTime) { this.ingestionTime = ingestionTime; }

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }
}
