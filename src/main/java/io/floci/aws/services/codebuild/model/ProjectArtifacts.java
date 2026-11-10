package io.floci.aws.services.codebuild.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectArtifacts {
    public ProjectArtifacts() {}

    private String type;
    private String location;
    private String path;
    private String namespaceType;
    private String name;
    private String packaging;
    private Boolean overrideArtifactName;
    private Boolean encryptionDisabled;
    private String artifactIdentifier;

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getNamespaceType() { return namespaceType; }
    public void setNamespaceType(String namespaceType) { this.namespaceType = namespaceType; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPackaging() { return packaging; }
    public void setPackaging(String packaging) { this.packaging = packaging; }

    public Boolean getOverrideArtifactName() { return overrideArtifactName; }
    public void setOverrideArtifactName(Boolean overrideArtifactName) { this.overrideArtifactName = overrideArtifactName; }

    public Boolean getEncryptionDisabled() { return encryptionDisabled; }
    public void setEncryptionDisabled(Boolean encryptionDisabled) { this.encryptionDisabled = encryptionDisabled; }

    public String getArtifactIdentifier() { return artifactIdentifier; }
    public void setArtifactIdentifier(String artifactIdentifier) { this.artifactIdentifier = artifactIdentifier; }
}
