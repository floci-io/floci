package io.floci.aws.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectSource {
    public ProjectSource() {}

    private String type;
    private String location;
    private Integer gitCloneDepth;
    private String buildspec;
    private Boolean reportBuildStatus;
    private Boolean insecureSsl;
    private String sourceIdentifier;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public Integer getGitCloneDepth() { return gitCloneDepth; }
    public void setGitCloneDepth(Integer gitCloneDepth) { this.gitCloneDepth = gitCloneDepth; }

    public String getBuildspec() { return buildspec; }
    public void setBuildspec(String buildspec) { this.buildspec = buildspec; }

    public Boolean getReportBuildStatus() { return reportBuildStatus; }
    public void setReportBuildStatus(Boolean reportBuildStatus) { this.reportBuildStatus = reportBuildStatus; }

    public Boolean getInsecureSsl() { return insecureSsl; }
    public void setInsecureSsl(Boolean insecureSsl) { this.insecureSsl = insecureSsl; }

    public String getSourceIdentifier() { return sourceIdentifier; }
    public void setSourceIdentifier(String sourceIdentifier) { this.sourceIdentifier = sourceIdentifier; }
}
