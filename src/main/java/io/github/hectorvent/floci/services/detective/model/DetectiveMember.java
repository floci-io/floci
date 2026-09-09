package io.github.hectorvent.floci.services.detective.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DetectiveMember {
    private String accountId;
    private String emailAddress;
    private String status;

    public DetectiveMember() {
    }

    public DetectiveMember(String accountId, String emailAddress, String status) {
        this.accountId = accountId;
        this.emailAddress = emailAddress;
        this.status = status;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getEmailAddress() { return emailAddress; }
    public void setEmailAddress(String emailAddress) { this.emailAddress = emailAddress; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
}
