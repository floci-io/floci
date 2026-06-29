package io.github.hectorvent.floci.services.iot.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class IotPolicy {
    private String policyName;
    private String policyArn;
    private String policyDocument;
    private String defaultVersionId;
    private Instant creationDate;
    private List<PolicyVersion> versions = new ArrayList<>();
    private Map<String, String> tags = new TreeMap<>();

    public String getPolicyName() { return policyName; }
    public void setPolicyName(String policyName) { this.policyName = policyName; }
    public String getPolicyArn() { return policyArn; }
    public void setPolicyArn(String policyArn) { this.policyArn = policyArn; }
    public String getPolicyDocument() { return policyDocument; }
    public void setPolicyDocument(String policyDocument) { this.policyDocument = policyDocument; }
    public String getDefaultVersionId() { return defaultVersionId; }
    public void setDefaultVersionId(String defaultVersionId) { this.defaultVersionId = defaultVersionId; }
    public Instant getCreationDate() { return creationDate; }
    public void setCreationDate(Instant creationDate) { this.creationDate = creationDate; }
    public List<PolicyVersion> getVersions() { return versions == null ? List.of() : versions; }
    public void setVersions(List<PolicyVersion> versions) { this.versions = versions == null ? new ArrayList<>() : new ArrayList<>(versions); }
    public Map<String, String> getTags() { return tags == null ? Map.of() : tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags == null ? new TreeMap<>() : new TreeMap<>(tags); }

    public static class PolicyVersion {
        private String versionId;
        private String document;
        private Instant createDate;

        public String getVersionId() { return versionId; }
        public void setVersionId(String versionId) { this.versionId = versionId; }
        public String getDocument() { return document; }
        public void setDocument(String document) { this.document = document; }
        public Instant getCreateDate() { return createDate; }
        public void setCreateDate(Instant createDate) { this.createDate = createDate; }
    }
}
