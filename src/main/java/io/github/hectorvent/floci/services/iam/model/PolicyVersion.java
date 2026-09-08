package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolicyVersion {

    private String versionId;
    private String document;
    private boolean defaultVersion;
    private Instant createDate;

    public PolicyVersion() {}

    public PolicyVersion(String versionId, String document, boolean defaultVersion) {
        this(versionId, document, defaultVersion, Instant.now());
    }

    public PolicyVersion(String versionId, String document, boolean defaultVersion, Instant createDate) {
        this.versionId = versionId;
        this.document = document;
        this.defaultVersion = defaultVersion;
        this.createDate = createDate;
    }

    public String getVersionId() { return versionId; }
    public void setVersionId(String versionId) { this.versionId = versionId; }

    public String getDocument() { return document; }
    public void setDocument(String document) { this.document = document; }

    public boolean isDefaultVersion() { return defaultVersion; }
    public void setDefaultVersion(boolean defaultVersion) { this.defaultVersion = defaultVersion; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }
}
