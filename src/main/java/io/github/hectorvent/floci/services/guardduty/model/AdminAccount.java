package io.github.hectorvent.floci.services.guardduty.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** The GuardDuty delegated administrator account for a region. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminAccount {
    private String adminAccountId;
    private String adminStatus;

    public AdminAccount() {
    }

    public AdminAccount(String adminAccountId, String adminStatus) {
        this.adminAccountId = adminAccountId;
        this.adminStatus = adminStatus;
    }

    public String getAdminAccountId() {
        return adminAccountId;
    }

    public void setAdminAccountId(String adminAccountId) {
        this.adminAccountId = adminAccountId;
    }

    public String getAdminStatus() {
        return adminStatus;
    }

    public void setAdminStatus(String adminStatus) {
        this.adminStatus = adminStatus;
    }
}
