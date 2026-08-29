package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Tracks an asynchronous {@code CreateAccount} or {@code CreateGovCloudAccount} request.
 *
 * <p>AWS returns this in {@code IN_PROGRESS} and the caller polls
 * {@code DescribeCreateAccountStatus}. Floci completes the account synchronously but keeps the
 * record so SDK code written against the real polling contract works unchanged.
 */
@RegisterForReflection
public class CreateAccountStatus {

    private String id;
    private String accountName;
    private String state;
    private Instant requestedTimestamp;
    private Instant completedTimestamp;
    private String accountId;
    private String govCloudAccountId;
    private String failureReason;
    private String organizationId;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAccountName() {
        return accountName;
    }

    public void setAccountName(String accountName) {
        this.accountName = accountName;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public Instant getRequestedTimestamp() {
        return requestedTimestamp;
    }

    public void setRequestedTimestamp(Instant requestedTimestamp) {
        this.requestedTimestamp = requestedTimestamp;
    }

    public Instant getCompletedTimestamp() {
        return completedTimestamp;
    }

    public void setCompletedTimestamp(Instant completedTimestamp) {
        this.completedTimestamp = completedTimestamp;
    }

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getGovCloudAccountId() {
        return govCloudAccountId;
    }

    public void setGovCloudAccountId(String govCloudAccountId) {
        this.govCloudAccountId = govCloudAccountId;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }
}
