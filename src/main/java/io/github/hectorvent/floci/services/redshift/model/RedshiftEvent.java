package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** An event as DescribeEvents reports it. */
@RegisterForReflection
public class RedshiftEvent {

    private String key;
    private Instant date;
    private String severity;
    private String eventId;
    private String sourceIdentifier;
    private String sourceType;
    private List<String> eventCategories = new ArrayList<>();
    private String message;

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }
    public Instant getDate() { return date; }
    public void setDate(Instant date) { this.date = date; }
    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getSourceIdentifier() { return sourceIdentifier; }
    public void setSourceIdentifier(String sourceIdentifier) { this.sourceIdentifier = sourceIdentifier; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public List<String> getEventCategories() { return eventCategories; }
    public void setEventCategories(List<String> eventCategories) { this.eventCategories = eventCategories; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}
