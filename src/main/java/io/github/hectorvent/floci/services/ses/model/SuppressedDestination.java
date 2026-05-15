package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SuppressedDestination {

    @JsonProperty("EmailAddress")
    private String emailAddress;

    @JsonProperty("Reason")
    private String reason;

    @JsonProperty("LastUpdateTime")
    private Instant lastUpdateTime;

    public SuppressedDestination() {}

    public SuppressedDestination(String emailAddress, String reason) {
        this.emailAddress = emailAddress;
        this.reason = reason;
        this.lastUpdateTime = Instant.now();
    }

    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }

    public Instant getLastUpdateTime() { return lastUpdateTime; }
    public void setLastUpdateTime(Instant lastUpdateTime) { this.lastUpdateTime = lastUpdateTime; }
}
