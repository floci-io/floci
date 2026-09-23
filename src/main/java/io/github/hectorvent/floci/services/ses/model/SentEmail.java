package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonAlias;
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

    @JsonProperty("Region")
    private String region;

    @JsonProperty("Source")
    private String source;

    @JsonProperty("ReturnPath")
    private String returnPath;

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
    @JsonAlias("Body")
    private String bodyText;

    @JsonProperty("BodyHtml")
    private String bodyHtml;

    @JsonProperty("RawData")
    private String rawData;

    @JsonProperty("Headers")
    private List<MessageHeader> headers;

    @JsonProperty("SentAt")
    private Instant sentAt;

    /**
     * Set when the content scan rejected the message after acceptance. A rejected record keeps only
     * its sender, its envelope and this reason, since the content is by definition something no
     * store should hold.
     */
    @JsonProperty("RejectReason")
    private String rejectReason;

    @JsonProperty("EmailTags")
    private List<MessageTag> emailTags;

    /**
     * Per-recipient event timelines, derived at send time and served by {@code GetMessageInsights}.
     */
    @JsonProperty("Insights")
    private List<EmailInsights> insights;

    public SentEmail() {}

    /** Constructor for Simple / Template content. */
    public SentEmail(String messageId, String region, String source,
                     List<String> toAddresses, List<String> ccAddresses,
                     List<String> bccAddresses, List<String> replyToAddresses,
                     String subject, String bodyText, String bodyHtml) {
        this.messageId = messageId;
        this.region = region;
        this.source = source;
        this.toAddresses = toAddresses;
        this.ccAddresses = ccAddresses;
        this.bccAddresses = bccAddresses;
        this.replyToAddresses = replyToAddresses;
        this.subject = subject;
        this.bodyText = bodyText;
        this.bodyHtml = bodyHtml;
        this.sentAt = Instant.now();
    }

    /** Constructor for Raw content. */
    public SentEmail(String messageId, String region, String source,
                     List<String> destinations, String rawData) {
        this.messageId = messageId;
        this.region = region;
        this.source = source;
        this.toAddresses = destinations;
        this.rawData = rawData;
        this.sentAt = Instant.now();
    }

    public String getMessageId() { return messageId; }
    public void setMessageId(String messageId) { this.messageId = messageId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getReturnPath() { return returnPath; }
    public void setReturnPath(String returnPath) { this.returnPath = returnPath; }

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

    public List<MessageHeader> getHeaders() { return headers; }
    public void setHeaders(List<MessageHeader> headers) { this.headers = headers; }

    public boolean isRaw() { return rawData != null; }

    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant sentAt) { this.sentAt = sentAt; }

    public String getRejectReason() { return rejectReason; }
    public void setRejectReason(String rejectReason) { this.rejectReason = rejectReason; }

    /**
     * Drops everything but the sender, the envelope and the reason; used when the content scan
     * rejects the message.
     */
    public void discardContent(String reason) {
        this.rejectReason = reason;
        this.replyToAddresses = null;
        this.subject = null;
        this.headers = null;
        this.bodyText = null;
        this.bodyHtml = null;
        this.rawData = null;
    }

    public List<MessageTag> getEmailTags() { return emailTags; }
    public void setEmailTags(List<MessageTag> emailTags) { this.emailTags = emailTags; }

    public List<EmailInsights> getInsights() { return insights; }
    public void setInsights(List<EmailInsights> insights) { this.insights = insights; }
}
