package io.github.hectorvent.floci.services.eventbridge.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
public class ArchivedEvent {

    private String eventId;
    private Instant eventTime;
    private String source;
    private String detailType;
    private String detail;
    private String eventBusArn;

    public ArchivedEvent() {}

    public ArchivedEvent(String eventId, Instant eventTime, String source,
                         String detailType, String detail, String eventBusArn) {
        this.eventId = eventId;
        this.eventTime = eventTime;
        this.source = source;
        this.detailType = detailType;
        this.detail = detail;
        this.eventBusArn = eventBusArn;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public Instant getEventTime() { return eventTime; }
    public void setEventTime(Instant eventTime) { this.eventTime = eventTime; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getDetailType() { return detailType; }
    public void setDetailType(String detailType) { this.detailType = detailType; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public String getEventBusArn() { return eventBusArn; }
    public void setEventBusArn(String eventBusArn) { this.eventBusArn = eventBusArn; }
}
