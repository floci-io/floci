package io.github.hectorvent.floci.services.swf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One entry in a workflow execution's history.
 *
 * <p>On the wire an event carries exactly one {@code <camelCaseEventType>EventAttributes}
 * member alongside {@code eventId}/{@code eventType}/{@code eventTimestamp}. The attribute
 * bag is kept generic here and serialized under the name derived from the event type, so
 * adding an event type does not require a new field on this class.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SwfHistoryEvent {

    private long eventId;
    private double eventTimestamp;
    private String eventType;
    private Map<String, Object> attributes = new LinkedHashMap<>();

    public SwfHistoryEvent() {
    }

    public SwfHistoryEvent(long eventId, double eventTimestamp, String eventType) {
        this.eventId = eventId;
        this.eventTimestamp = eventTimestamp;
        this.eventType = eventType;
    }

    public long getEventId() {
        return eventId;
    }

    public void setEventId(long eventId) {
        this.eventId = eventId;
    }

    public double getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(double eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public Map<String, Object> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, Object> attributes) {
        this.attributes = attributes != null ? attributes : new LinkedHashMap<>();
    }

    public SwfHistoryEvent attr(String key, Object value) {
        if (value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    /**
     * The wire name of this event's attribute member: the event type with a lower-cased
     * first letter plus {@code EventAttributes} (WorkflowExecutionStarted becomes
     * workflowExecutionStartedEventAttributes).
     */
    public String attributesFieldName() {
        return Character.toLowerCase(eventType.charAt(0)) + eventType.substring(1) + "EventAttributes";
    }
}
