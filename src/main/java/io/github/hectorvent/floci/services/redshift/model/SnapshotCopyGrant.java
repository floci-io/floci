package io.github.hectorvent.floci.services.redshift.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

@RegisterForReflection
public class SnapshotCopyGrant {
    private String snapshotCopyGrantName;
    private String kmsKeyId;
    private Map<String, String> tags = new LinkedHashMap<>();

    public SnapshotCopyGrant() {}

    public SnapshotCopyGrant(String snapshotCopyGrantName, String kmsKeyId) {
        this.snapshotCopyGrantName = snapshotCopyGrantName;
        this.kmsKeyId = kmsKeyId;
    }

    public String getSnapshotCopyGrantName() { return snapshotCopyGrantName; }
    public void setSnapshotCopyGrantName(String snapshotCopyGrantName) { this.snapshotCopyGrantName = snapshotCopyGrantName; }
    public String getKmsKeyId() { return kmsKeyId; }
    public void setKmsKeyId(String kmsKeyId) { this.kmsKeyId = kmsKeyId; }
    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
