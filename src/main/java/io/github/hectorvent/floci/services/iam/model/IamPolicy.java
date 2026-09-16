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
     * Left null (rather than defaulted) so a policy persisted before this field existed is
     * distinguishable from a genuinely fresh one: Jackson leaves an absent JSON field at null,
     * while every policy constructed since this field shipped explicitly sets it below. A null
     * value is treated at the call site as "true history unknown" and given a defensive margin
     * beyond the live keys' own max, since the live keys alone cannot reveal a version deleted
     * before this field ever existed on disk.
     */
    private Integer nextVersionNumber;
    private Instant createDate;
    private Instant updateDate;
    private Map<String, String> tags = new ConcurrentHashMap<>();
    // versionId -> PolicyVersion (ordered for consistent listing)
    private Map<String, PolicyVersion> versions = Collections.synchronizedMap(new LinkedHashMap<>());

    public IamPolicy() {}

    public IamPolicy(String policyId, String policyName, String path, String arn,
                     String description, String document) {
        this(policyId, policyName, path, arn, description, new PolicyVersion("v1", document, true));
    }

    private IamPolicy(String policyId, String policyName, String path, String arn,
                      String description, PolicyVersion v1) {
        this(policyId, policyName, path, arn, description, v1, v1.getCreateDate(), v1.getCreateDate());
    }

    /**
     * Creates a policy whose single stored version is {@code defaultVersion}, which need not
     * be {@code v1}. AWS-managed policies are bundled at whatever version AWS has revised them
     * to (for example {@code v3}), with only that version's document available, so the version
     * counter starts just past it. {@code createDate} is when the policy itself was first
     * published and {@code updateDate} when its current default version was, the two dates
     * {@code GetPolicy} reports separately.
     */
    public IamPolicy(String policyId, String policyName, String path, String arn,
                     String description, PolicyVersion defaultVersion,
                     Instant createDate, Instant updateDate) {
        this.policyId = policyId;
        this.policyName = policyName;
        this.path = path;
        this.arn = arn;
        this.description = description;
        this.createDate = createDate;
        this.updateDate = updateDate;
        defaultVersion.setDefaultVersion(true);
        this.defaultVersionId = defaultVersion.getVersionId();
        this.versions.put(defaultVersion.getVersionId(), defaultVersion);
        this.nextVersionNumber = Integer.parseInt(defaultVersion.getVersionId().substring(1)) + 1;
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

    public Integer getNextVersionNumber() { return nextVersionNumber; }
    public void setNextVersionNumber(Integer nextVersionNumber) { this.nextVersionNumber = nextVersionNumber; }

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
