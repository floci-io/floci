package io.github.hectorvent.floci.services.inspector2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InspectorState {
    private String adminAccountId;
    private String status = "DISABLED";
    private int enablingPollsRemaining;
    private String ec2Status = "DISABLED";
    private String ecrStatus = "DISABLED";
    private String lambdaStatus = "DISABLED";
    private String lambdaCodeStatus = "DISABLED";
    private String codeRepositoryStatus = "DISABLED";
    private boolean autoEnableEc2;
    private boolean autoEnableEcr;
    private boolean autoEnableLambda;
    private boolean autoEnableLambdaCode;
    private boolean autoEnableCodeRepository;

    public InspectorState() {
    }

    public String getAdminAccountId() { return adminAccountId; }
    public void setAdminAccountId(String adminAccountId) { this.adminAccountId = adminAccountId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public int getEnablingPollsRemaining() { return enablingPollsRemaining; }
    public void setEnablingPollsRemaining(int enablingPollsRemaining) { this.enablingPollsRemaining = enablingPollsRemaining; }
    public String getEc2Status() { return ec2Status; }
    public void setEc2Status(String value) { ec2Status = value; }
    public String getEcrStatus() { return ecrStatus; }
    public void setEcrStatus(String value) { ecrStatus = value; }
    public String getLambdaStatus() { return lambdaStatus; }
    public void setLambdaStatus(String value) { lambdaStatus = value; }
    public String getLambdaCodeStatus() { return lambdaCodeStatus; }
    public void setLambdaCodeStatus(String value) { lambdaCodeStatus = value; }
    public String getCodeRepositoryStatus() { return codeRepositoryStatus; }
    public void setCodeRepositoryStatus(String value) { codeRepositoryStatus = value; }
    public boolean isAutoEnableEc2() { return autoEnableEc2; }
    public void setAutoEnableEc2(boolean value) { autoEnableEc2 = value; }
    public boolean isAutoEnableEcr() { return autoEnableEcr; }
    public void setAutoEnableEcr(boolean value) { autoEnableEcr = value; }
    public boolean isAutoEnableLambda() { return autoEnableLambda; }
    public void setAutoEnableLambda(boolean value) { autoEnableLambda = value; }
    public boolean isAutoEnableLambdaCode() { return autoEnableLambdaCode; }
    public void setAutoEnableLambdaCode(boolean value) { autoEnableLambdaCode = value; }
    public boolean isAutoEnableCodeRepository() { return autoEnableCodeRepository; }
    public void setAutoEnableCodeRepository(boolean value) { autoEnableCodeRepository = value; }

    public String resourceStatus(String resourceType) {
        return switch (resourceType) {
            case "EC2" -> ec2Status;
            case "ECR" -> ecrStatus;
            case "LAMBDA" -> lambdaStatus;
            case "LAMBDA_CODE" -> lambdaCodeStatus;
            case "CODE_REPOSITORY" -> codeRepositoryStatus;
            default -> throw new IllegalArgumentException("Unknown Inspector resource type: " + resourceType);
        };
    }

    public void setResourceStatus(String resourceType, String value) {
        switch (resourceType) {
            case "EC2" -> ec2Status = value;
            case "ECR" -> ecrStatus = value;
            case "LAMBDA" -> lambdaStatus = value;
            case "LAMBDA_CODE" -> lambdaCodeStatus = value;
            case "CODE_REPOSITORY" -> codeRepositoryStatus = value;
            default -> throw new IllegalArgumentException("Unknown Inspector resource type: " + resourceType);
        }
    }
}
