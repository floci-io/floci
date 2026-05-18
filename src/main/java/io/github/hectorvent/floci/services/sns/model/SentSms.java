package io.github.hectorvent.floci.services.sns.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Record of an SMS published via {@code SnsService.publish(...)} with a phone
 * number. Stored so test helpers and local clients can inspect SMS content
 * via {@code GET /_aws/sns} (mirrors the SES inspection pattern).
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SentSms {

    @JsonProperty("MessageId")
    private String messageId;

    @JsonProperty("Region")
    private String region;

    @JsonProperty("PhoneNumber")
    private String phoneNumber;

    @JsonProperty("Message")
    private String message;

    @JsonProperty("Subject")
    private String subject;

    @JsonProperty("SentAt")
    private Instant sentAt;

    public SentSms() {}

    public SentSms(String messageId, String region, String phoneNumber,
                   String message, String subject, Instant sentAt) {
        this.messageId = messageId;
        this.region = region;
        this.phoneNumber = phoneNumber;
        this.message = message;
        this.subject = subject;
        this.sentAt = sentAt;
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
