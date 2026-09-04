package io.github.hectorvent.floci.services.securityhub.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SecurityHubAssociation {
    private String targetId;
    private String targetType;
    private String policyId;
    private String status;
    private String statusMessage;
    private int pendingPollsRemaining;
    private boolean disassociating;

    public SecurityHubAssociation() {
    }

    public SecurityHubAssociation(String targetId, String targetType, String policyId,
                                  String status, int pendingPollsRemaining) {
        this.targetId = targetId;
        this.targetType = targetType;
        this.policyId = policyId;
        this.status = status;
        this.pendingPollsRemaining = pendingPollsRemaining;
    }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }
    public String getTargetType() { return targetType; }
    public void setTargetType(String targetType) { this.targetType = targetType; }
    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String statusMessage) { this.statusMessage = statusMessage; }
    public int getPendingPollsRemaining() { return pendingPollsRemaining; }
    public void setPendingPollsRemaining(int pendingPollsRemaining) { this.pendingPollsRemaining = pendingPollsRemaining; }
    public boolean isDisassociating() { return disassociating; }
    public void setDisassociating(boolean disassociating) { this.disassociating = disassociating; }

    public SecurityHubAssociation copy() {
        SecurityHubAssociation copy = new SecurityHubAssociation(targetId, targetType, policyId, status,
                pendingPollsRemaining);
        copy.setStatusMessage(statusMessage);
        copy.setDisassociating(disassociating);
        return copy;
    }
}
