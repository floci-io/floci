package io.floci.aws.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectEnvironment {
    public ProjectEnvironment() {}

    private String type;
    private String image;
    private String computeType;
    private List<Map<String, String>> environmentVariables;
    private Boolean privilegedMode;
    private String certificate;
    private String imagePullCredentialsType;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getImage() { return image; }
    public void setImage(String image) { this.image = image; }

    public String getComputeType() { return computeType; }
    public void setComputeType(String computeType) { this.computeType = computeType; }

    public List<Map<String, String>> getEnvironmentVariables() { return environmentVariables; }
    public void setEnvironmentVariables(List<Map<String, String>> environmentVariables) { this.environmentVariables = environmentVariables; }

    public Boolean getPrivilegedMode() { return privilegedMode; }
    public void setPrivilegedMode(Boolean privilegedMode) { this.privilegedMode = privilegedMode; }

    public String getCertificate() { return certificate; }
    public void setCertificate(String certificate) { this.certificate = certificate; }

    public String getImagePullCredentialsType() { return imagePullCredentialsType; }
    public void setImagePullCredentialsType(String imagePullCredentialsType) { this.imagePullCredentialsType = imagePullCredentialsType; }
}
