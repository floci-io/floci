package io.github.hectorvent.floci.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.List;

/** A Lambda code signing configuration, as CreateCodeSigningConfig returns it. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CodeSigningConfig {

    @JsonProperty("CodeSigningConfigId")
    private String codeSigningConfigId;

    @JsonProperty("CodeSigningConfigArn")
    private String codeSigningConfigArn;

    @JsonProperty("Description")
    private String description;

    @JsonProperty("AllowedPublishers")
    private AllowedPublishers allowedPublishers;

    @JsonProperty("CodeSigningPolicies")
    private CodeSigningPolicies codeSigningPolicies;

    @JsonProperty("LastModified")
    private String lastModified;

    public String getCodeSigningConfigId() { return codeSigningConfigId; }
    public void setCodeSigningConfigId(String codeSigningConfigId) { this.codeSigningConfigId = codeSigningConfigId; }
    public String getCodeSigningConfigArn() { return codeSigningConfigArn; }
    public void setCodeSigningConfigArn(String codeSigningConfigArn) { this.codeSigningConfigArn = codeSigningConfigArn; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public AllowedPublishers getAllowedPublishers() { return allowedPublishers; }
    public void setAllowedPublishers(AllowedPublishers allowedPublishers) { this.allowedPublishers = allowedPublishers; }
    public CodeSigningPolicies getCodeSigningPolicies() { return codeSigningPolicies; }
    public void setCodeSigningPolicies(CodeSigningPolicies codeSigningPolicies) { this.codeSigningPolicies = codeSigningPolicies; }
    public String getLastModified() { return lastModified; }
    public void setLastModified(String lastModified) { this.lastModified = lastModified; }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AllowedPublishers {
        @JsonProperty("SigningProfileVersionArns")
        private List<String> signingProfileVersionArns = new ArrayList<>();

        public List<String> getSigningProfileVersionArns() { return signingProfileVersionArns; }
        public void setSigningProfileVersionArns(List<String> signingProfileVersionArns) {
            this.signingProfileVersionArns = signingProfileVersionArns;
        }
    }

    @RegisterForReflection
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class CodeSigningPolicies {
        @JsonProperty("UntrustedArtifactOnDeployment")
        private String untrustedArtifactOnDeployment;

        public String getUntrustedArtifactOnDeployment() { return untrustedArtifactOnDeployment; }
        public void setUntrustedArtifactOnDeployment(String untrustedArtifactOnDeployment) {
            this.untrustedArtifactOnDeployment = untrustedArtifactOnDeployment;
        }
    }
}
