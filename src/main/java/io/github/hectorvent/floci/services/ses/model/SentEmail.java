package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SentEmail {

    @JsonProperty("MessageId")
    private String messageId;

    @JsonProperty("Source")
    private String source;

    @JsonProperty("Destination")
    private List<String> toAddresses;

    @JsonProperty("CcAddresses")
    private List<String> ccAddresses;

    @JsonProperty("BccAddresses")
    private List<String> bccAddresses;

    @JsonProperty("Subject")
    private String subject;

    @JsonProperty("ReplyToAddresses")
    private List<String> replyToAddresses;

    @JsonProperty("BodyText")
    private String bodyText;

    @JsonProperty("BodyHtml")
    private String bodyHtml;

    @JsonProperty("RawData")
    private String rawData;

    @JsonProperty("SentAt")
    private Instant sentAt;

    public SentEmail() {}

    /** Constructor for Simple / Template content. */
    public SentEmail(String messageId, String source, List<String> toAddresses,
                     List<String> ccAddresses, List<String> bccAddresses,
                     String subject, String bodyText, String bodyHtml) {
        this.messageId = messageId;
        this.source = source;
        this.toAddresses = toAddresses;
        this.ccAddresses = ccAddresses;
        this.bccAddresses = bccAddresses;
        this.subject = subject;
        this.bodyText = bodyText;
        this.bodyHtml = bodyHtml;
        this.sentAt = Instant.now();
    }

    /** Constructor for Raw content. */
    public SentEmail(String messageId, String source, List<String> destinations, String rawData) {
        this.messageId = messageId;
        this.source = source;
        this.toAddresses = destinations;
        this.rawData = rawData;
        this.sentAt = Instant.now();
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public List<String> getToAddresses() { return toAddresses; }
    public void setToAddresses(List<String> toAddresses) { this.toAddresses = toAddresses; }

    public List<String> getCcAddresses() { return ccAddresses; }
    public void setCcAddresses(List<String> ccAddresses) { this.ccAddresses = ccAddresses; }

    public List<String> getBccAddresses() { return bccAddresses; }
    public void setBccAddresses(List<String> bccAddresses) { this.bccAddresses = bccAddresses; }

    public String getSubject() { return subject; }
    public void setSubject(String subject) { this.subject = subject; }

    public List<String> getReplyToAddresses() { return replyToAddresses; }
    public void setReplyToAddresses(List<String> replyToAddresses) { this.replyToAddresses = replyToAddresses; }

    public String getBodyText() { return bodyText; }
    public void setBodyText(String bodyText) { this.bodyText = bodyText; }

    public String getBodyHtml() { return bodyHtml; }
    public void setBodyHtml(String bodyHtml) { this.bodyHtml = bodyHtml; }

    public String getRawData() { return rawData; }
    public void setRawData(String rawData) { this.rawData = rawData; }

    public boolean isRaw() { return rawData != null; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }
}
