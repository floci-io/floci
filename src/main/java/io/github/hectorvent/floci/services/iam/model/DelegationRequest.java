package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * One IAM delegation request, the {@code DelegationRequest} shape.
 *
 * <p>A request is created by the partner and acted on by the customer, so it is the one IAM record
 * two accounts share. The fields say which side is which: {@code requestorId} is the partner that
 * created it, {@code ownerAccountId} the account it is aimed at or the one that claimed it,
 * {@code ownerId} the identity within that account, and {@code approverId} whoever accepted it.
 *
 * <p>{@code notes} and {@code rejectionReason} both exist on the shape and each has exactly one
 * writer: {@code UpdateDelegationRequest} carries notes for an administrator, and
 * {@code RejectDelegationRequest} carries the explanation for the refusal.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class DelegationRequest {

    private String delegationRequestId;
    private String description;
    private String requestMessage;
    private DelegationPermission permissions;
    private String permissionPolicy;
    private List<String> rolePermissionRestrictionArns = new ArrayList<>();
    private String notificationChannel;
    private String requestorWorkflowId;
    private String requestorId;
    private String requestorName;
    private String ownerAccountId;
    private String ownerId;
    private String approverId;
    private DelegationRequestState state = DelegationRequestState.UNASSIGNED;
    private Integer sessionDuration;
    private String redirectUrl;
    private String notes;
    private String rejectionReason;
    private Boolean onlySendByOwner;
    private Instant createDate;
    private Instant updatedTime;
    private Instant expirationTime;

    public DelegationRequest() {}

    public String getDelegationRequestId() { return delegationRequestId; }
    public void setDelegationRequestId(String delegationRequestId) { this.delegationRequestId = delegationRequestId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getRequestMessage() { return requestMessage; }
    public void setRequestMessage(String requestMessage) { this.requestMessage = requestMessage; }

    public DelegationPermission getPermissions() { return permissions; }
    public void setPermissions(DelegationPermission permissions) { this.permissions = permissions; }

    public String getPermissionPolicy() { return permissionPolicy; }
    public void setPermissionPolicy(String permissionPolicy) { this.permissionPolicy = permissionPolicy; }

    public List<String> getRolePermissionRestrictionArns() { return rolePermissionRestrictionArns; }

    public void setRolePermissionRestrictionArns(List<String> rolePermissionRestrictionArns) {
        this.rolePermissionRestrictionArns = rolePermissionRestrictionArns == null
                ? new ArrayList<>() : new ArrayList<>(rolePermissionRestrictionArns);
    }

    /**
     * The SNS topic IAM publishes state changes to. Held but unused until the notifications land:
     * the create call validates it, and nothing reads it yet.
     */
    public String getNotificationChannel() { return notificationChannel; }
    public void setNotificationChannel(String notificationChannel) { this.notificationChannel = notificationChannel; }

    public String getRequestorWorkflowId() { return requestorWorkflowId; }
    public void setRequestorWorkflowId(String requestorWorkflowId) { this.requestorWorkflowId = requestorWorkflowId; }

    public String getRequestorId() { return requestorId; }
    public void setRequestorId(String requestorId) { this.requestorId = requestorId; }

    public String getRequestorName() { return requestorName; }
    public void setRequestorName(String requestorName) { this.requestorName = requestorName; }

    public String getOwnerAccountId() { return ownerAccountId; }
    public void setOwnerAccountId(String ownerAccountId) { this.ownerAccountId = ownerAccountId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getApproverId() { return approverId; }
    public void setApproverId(String approverId) { this.approverId = approverId; }

    public DelegationRequestState getState() { return state; }
    public void setState(DelegationRequestState state) { this.state = state; }

    public Integer getSessionDuration() { return sessionDuration; }
    public void setSessionDuration(Integer sessionDuration) { this.sessionDuration = sessionDuration; }

    public String getRedirectUrl() { return redirectUrl; }
    public void setRedirectUrl(String redirectUrl) { this.redirectUrl = redirectUrl; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Boolean getOnlySendByOwner() { return onlySendByOwner; }
    public void setOnlySendByOwner(Boolean onlySendByOwner) { this.onlySendByOwner = onlySendByOwner; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Instant getUpdatedTime() { return updatedTime; }
    public void setUpdatedTime(Instant updatedTime) { this.updatedTime = updatedTime; }

    public Instant getExpirationTime() { return expirationTime; }
    public void setExpirationTime(Instant expirationTime) { this.expirationTime = expirationTime; }
}
