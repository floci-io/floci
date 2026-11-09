package io.floci.aws.services.securityhub.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** A member account of a Security Hub administrator, stored in the administrator's state. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityHubMember {
    private String accountId;
    private String email;
    private String administratorId;
    private String memberStatus;
    private boolean organizationMember;
    private String invitedAt;
    private String updatedAt;

    public SecurityHubMember() {
    }

    public SecurityHubMember(String accountId, String email, String administratorId, String memberStatus,
                             boolean organizationMember, String invitedAt, String updatedAt) {
        this.accountId = accountId;
        this.email = email;
        this.administratorId = administratorId;
        this.memberStatus = memberStatus;
        this.organizationMember = organizationMember;
        this.invitedAt = invitedAt;
        this.updatedAt = updatedAt;
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getAdministratorId() { return administratorId; }
    public void setAdministratorId(String administratorId) { this.administratorId = administratorId; }
    public String getMemberStatus() { return memberStatus; }
    public void setMemberStatus(String memberStatus) { this.memberStatus = memberStatus; }
    public boolean isOrganizationMember() { return organizationMember; }
    public void setOrganizationMember(boolean organizationMember) { this.organizationMember = organizationMember; }
    public String getInvitedAt() { return invitedAt; }
    public void setInvitedAt(String invitedAt) { this.invitedAt = invitedAt; }
    public String getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(String updatedAt) { this.updatedAt = updatedAt; }

    public SecurityHubMember copy() {
        return new SecurityHubMember(accountId, email, administratorId, memberStatus, organizationMember,
                invitedAt, updatedAt);
    }
}
