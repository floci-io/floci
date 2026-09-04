package io.github.hectorvent.floci.services.inspector2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectorState {
    private String adminAccountId;
    private String status = "DISABLED";
    private int enablingPollsRemaining;
    private boolean autoEnableEc2;
    private boolean autoEnableEcr;
    private boolean autoEnableLambda;
    private boolean autoEnableLambdaCode;

    public InspectorState() {
    }

    public String getAdminAccountId() { return adminAccountId; }
    public void setAdminAccountId(String adminAccountId) { this.adminAccountId = adminAccountId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getEnablingPollsRemaining() { return enablingPollsRemaining; }
    public void setEnablingPollsRemaining(int enablingPollsRemaining) { this.enablingPollsRemaining = enablingPollsRemaining; }
    public boolean isAutoEnableEc2() { return autoEnableEc2; }
    public void setAutoEnableEc2(boolean value) { autoEnableEc2 = value; }
    public boolean isAutoEnableEcr() { return autoEnableEcr; }
    public void setAutoEnableEcr(boolean value) { autoEnableEcr = value; }
    public boolean isAutoEnableLambda() { return autoEnableLambda; }
    public void setAutoEnableLambda(boolean value) { autoEnableLambda = value; }
    public boolean isAutoEnableLambdaCode() { return autoEnableLambdaCode; }
    public void setAutoEnableLambdaCode(boolean value) { autoEnableLambdaCode = value; }
}
