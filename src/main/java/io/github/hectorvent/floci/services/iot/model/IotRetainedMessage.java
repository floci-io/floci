package io.github.hectorvent.floci.services.iot.model;

import java.time.Instant;

public class IotRetainedMessage {
    private String topic;
    private String payload;
    private int qos;
    private Instant lastModifiedTime;

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public int getQos() { return qos; }
    public void setQos(int qos) { this.qos = qos; }
    public Instant getLastModifiedTime() { return lastModifiedTime; }
    public void setLastModifiedTime(Instant lastModifiedTime) { this.lastModifiedTime = lastModifiedTime; }
}
