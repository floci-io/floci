package io.github.hectorvent.floci.services.iam.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class IamPolicy {

    private String policyId;
    private String policyName;
    private String path;
    private String arn;
    private String description;
    private String defaultVersionId = "v1";
    private int attachmentCount = 0;
    /**
     * High-water mark for issued version numbers, so a deleted version's id is never reissued.
     * Defensive at the call site too (never trusted below the live keys' own max + 1): a policy
     * persisted before this field existed rehydrates it at the initializer value below, which
     * could otherwise be lower than versions already on disk.
     */
    private int nextVersionNumber = 2;
    private Instant createDate;
    private Instant updateDate;
    private Map<String, String> tags = new ConcurrentHashMap<>();
    // versionId -> PolicyVersion (ordered for consistent listing)
    private Map<String, PolicyVersion> versions = Collections.synchronizedMap(new LinkedHashMap<>());

    public IamPolicy() {}

    public IamPolicy(String policyId, String policyName, String path, String arn,
                     String description, String document) {
        this.policyId = policyId;
        this.policyName = policyName;
        this.path = path;
        this.arn = arn;
        this.description = description;
        this.createDate = Instant.now();
        this.updateDate = Instant.now();
        PolicyVersion v1 = new PolicyVersion("v1", document, true);
        this.versions.put("v1", v1);
    }

    public String getDefaultDocument() {
        PolicyVersion v = versions.get(defaultVersionId);
        return v != null ? v.getDocument() : null;
    }

    public String getPolicyId() { return policyId; }
    public void setPolicyId(String policyId) { this.policyId = policyId; }

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String policyName) { this.policyName = policyName; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDefaultVersionId() { return defaultVersionId; }
    public void setDefaultVersionId(String defaultVersionId) { this.defaultVersionId = defaultVersionId; }

    public int getAttachmentCount() { return attachmentCount; }
    public void setAttachmentCount(int attachmentCount) { this.attachmentCount = attachmentCount; }

    public int getNextVersionNumber() { return nextVersionNumber; }
    public void setNextVersionNumber(int nextVersionNumber) { this.nextVersionNumber = nextVersionNumber; }

    public Instant getCreateDate() { return createDate; }
    public void setCreateDate(Instant createDate) { this.createDate = createDate; }

    public Instant getUpdateDate() { return updateDate; }
    public void setUpdateDate(Instant updateDate) { this.updateDate = updateDate; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = new ConcurrentHashMap<>(tags);
    }

    public Map<String, PolicyVersion> getVersions() { return versions; }
    public void setVersions(Map<String, PolicyVersion> versions) {
        this.versions = Collections.synchronizedMap(new LinkedHashMap<>(versions));
    }
}
