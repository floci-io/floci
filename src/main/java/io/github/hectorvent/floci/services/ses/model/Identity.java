package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Identity {

    @JsonProperty("Identity")
    private String identity;

    @JsonProperty("IdentityType")
    private String identityType; // "EmailAddress" or "Domain"

    @JsonProperty("VerificationStatus")
    private String verificationStatus; // "Pending", "Success", "Failed", "TemporaryFailure", "NotStarted"

    @JsonProperty("VerificationToken")
    private String verificationToken;

    @JsonProperty("DkimEnabled")
    private boolean dkimEnabled;

    @JsonProperty("DkimVerificationStatus")
    private String dkimVerificationStatus;

    @JsonProperty("NotificationAttributes")
    private Map<String, String> notificationAttributes = new HashMap<>();

    @JsonProperty("FeedbackForwardingEnabled")
    private boolean feedbackForwardingEnabled = true;

    @JsonProperty("MailFromDomain")
    private String mailFromDomain;

    @JsonProperty("BehaviorOnMxFailure")
    private String behaviorOnMxFailure = "UseDefaultValue";

    @JsonProperty("MailFromDomainStatus")
    private String mailFromDomainStatus = "Pending";

    @JsonProperty("HeadersInNotificationsEnabled")
    private Map<String, Boolean> headersInNotificationsEnabled = new HashMap<>();

    @JsonProperty("CreatedAt")
    private Instant createdAt;

    @JsonProperty("Tags")
    private List<Tag> tags = new ArrayList<>();

    public Identity() {}

    public Identity(String identity, String identityType) {
        this.identity = identity;
        this.identityType = identityType;
        this.verificationStatus = "Success"; // auto-verify in emulator
        this.verificationToken = java.util.UUID.randomUUID().toString();
        this.dkimEnabled = false;
        this.dkimVerificationStatus = "NotStarted";
        this.createdAt = Instant.now();
    }

    public String getIdentity() { return identity; }
    public void setIdentity(String identity) { this.identity = identity; }

    public String getIdentityType() { return identityType; }
    public void setIdentityType(String identityType) { this.identityType = identityType; }

    public String getVerificationStatus() { return verificationStatus; }
    public void setVerificationStatus(String verificationStatus) { this.verificationStatus = verificationStatus; }

    public String getVerificationToken() { return verificationToken; }
    public void setVerificationToken(String verificationToken) { this.verificationToken = verificationToken; }

    public boolean isDkimEnabled() { return dkimEnabled; }
    public void setDkimEnabled(boolean dkimEnabled) { this.dkimEnabled = dkimEnabled; }

    public String getDkimVerificationStatus() { return dkimVerificationStatus; }
    public void setDkimVerificationStatus(String dkimVerificationStatus) { this.dkimVerificationStatus = dkimVerificationStatus; }

    public Map<String, String> getNotificationAttributes() { return notificationAttributes; }
    public void setNotificationAttributes(Map<String, String> notificationAttributes) { this.notificationAttributes = notificationAttributes; }

    public boolean isFeedbackForwardingEnabled() { return feedbackForwardingEnabled; }
    public void setFeedbackForwardingEnabled(boolean feedbackForwardingEnabled) { this.feedbackForwardingEnabled = feedbackForwardingEnabled; }

    public String getMailFromDomain() { return mailFromDomain; }
    public void setMailFromDomain(String mailFromDomain) { this.mailFromDomain = mailFromDomain; }

    public String getBehaviorOnMxFailure() { return behaviorOnMxFailure; }
    public void setBehaviorOnMxFailure(String behaviorOnMxFailure) { this.behaviorOnMxFailure = behaviorOnMxFailure; }

    public String getMailFromDomainStatus() { return mailFromDomainStatus; }
    public void setMailFromDomainStatus(String mailFromDomainStatus) { this.mailFromDomainStatus = mailFromDomainStatus; }

    public Map<String, Boolean> getHeadersInNotificationsEnabled() { return headersInNotificationsEnabled; }
    public void setHeadersInNotificationsEnabled(Map<String, Boolean> headersInNotificationsEnabled) { this.headersInNotificationsEnabled = headersInNotificationsEnabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags != null ? tags : new ArrayList<>(); }
}
