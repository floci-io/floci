package io.github.hectorvent.floci.services.ec2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A customer-managed or AWS-managed prefix list.
 *
 * <p>Entries are versioned: every successful modification stores a new version and bumps
 * {@link #version}, so {@code GetManagedPrefixListEntries} can serve a historical
 * {@code TargetVersion} the way AWS does. {@link #entriesByVersion} is the full history;
 * the current entries are the highest version.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ManagedPrefixList {

    private String prefixListId;
    private String prefixListName;
    private String prefixListArn;
    private String addressFamily;
    private String state = "create-complete";
    private String stateMessage;
    private Integer maxEntries;
    private long version = 1;
    private String ownerId;
    private String region;
    private boolean awsManaged;
    private Map<String, List<PrefixListEntry>> entriesByVersion = new LinkedHashMap<>();
    private List<Tag> tags = new ArrayList<>();

    public ManagedPrefixList() {}

    public String getPrefixListId() { return prefixListId; }
    public void setPrefixListId(String prefixListId) { this.prefixListId = prefixListId; }

    public String getPrefixListName() { return prefixListName; }
    public void setPrefixListName(String prefixListName) { this.prefixListName = prefixListName; }

    public String getPrefixListArn() { return prefixListArn; }
    public void setPrefixListArn(String prefixListArn) { this.prefixListArn = prefixListArn; }

    public String getAddressFamily() { return addressFamily; }
    public void setAddressFamily(String addressFamily) { this.addressFamily = addressFamily; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getStateMessage() { return stateMessage; }
    public void setStateMessage(String stateMessage) { this.stateMessage = stateMessage; }

    public Integer getMaxEntries() { return maxEntries; }
    public void setMaxEntries(Integer maxEntries) { this.maxEntries = maxEntries; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    /** AWS-managed lists (the gateway-endpoint service lists) are read-only. */
    public boolean isAwsManaged() { return awsManaged; }
    public void setAwsManaged(boolean awsManaged) { this.awsManaged = awsManaged; }

    // Keyed by version as a string: Jackson writes non-String map keys as strings anyway, so
    // using String here keeps the persisted JSON stable across a serialize/deserialize round trip.
    public Map<String, List<PrefixListEntry>> getEntriesByVersion() { return entriesByVersion; }
    public void setEntriesByVersion(Map<String, List<PrefixListEntry>> entriesByVersion) {
        this.entriesByVersion = entriesByVersion;
    }

    public List<Tag> getTags() { return tags; }
    public void setTags(List<Tag> tags) { this.tags = tags; }

    /** Entries for the current version, or an empty list if the version holds none. */
    public List<PrefixListEntry> currentEntries() {
        return entriesByVersion.getOrDefault(String.valueOf(version), List.of());
    }
}
