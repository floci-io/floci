package io.github.hectorvent.floci.services.msk.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

// A configuration persisted by the previous (pre-revision-history) schema stored one
// "latestRevision" object plus a flat "serverProperties" string instead of a revision list.
// Both keys are mapped back onto revision 1 at load time (see the legacy setters at the bottom
// of this class), so an entry written before this schema still reports a latestRevision and can
// still be updated, rather than loading as a configuration with no revision history at all.
// ignoreUnknown stays for anything else an older writer may have left behind: without it a
// single stale key would fail the whole msk-configurations.json load, not just that entry.
@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class MskConfiguration {

    @JsonProperty("arn")
    private String arn;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("kafkaVersions")
    private List<String> kafkaVersions;

    @JsonProperty("state")
    private ConfigurationState state;

    @JsonProperty("creationTime")
    private Instant creationTime;

    // Full revision history, oldest first. AWS's Configuration/CreateConfigurationResponse/
    // DescribeConfigurationResponse shapes only ever expose the latest one (see
    // getLatestRevision()); the full list backs ListConfigurationRevisions.
    //
    // Concurrent-safe by construction (CopyOnWriteArrayList/ConcurrentHashMap), not just by
    // convention: getLatestRevision() and the getters below are read from MskController with no
    // access to MskService#configurationUpdateLock, so a plain ArrayList/HashMap here would let
    // an in-flight updateConfiguration() on another thread corrupt or crash an unrelated read.
    @JsonProperty("revisions")
    private List<ConfigurationRevision> revisions = new CopyOnWriteArrayList<>();

    // Decoded server.properties content, keyed by revision. AWS never returns this on the
    // configuration/latestRevision shapes - only DescribeConfigurationRevision - so it's kept
    // out of ConfigurationRevision itself, which those other shapes serialize directly.
    @JsonProperty("serverPropertiesByRevision")
    private Map<Long, String> serverPropertiesByRevision = new ConcurrentHashMap<>();

    @JsonIgnore
    private String accountId;

    public MskConfiguration() {}

    public MskConfiguration(String arn, String name, String description,
                             List<String> kafkaVersions, String serverProperties) {
        this.arn = arn;
        this.name = name;
        this.description = description;
        this.kafkaVersions = kafkaVersions;
        this.state = ConfigurationState.ACTIVE;
        this.creationTime = Instant.now();
        addRevision(new ConfigurationRevision(1L, this.creationTime, description), serverProperties);
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getKafkaVersions() { return kafkaVersions; }
    public void setKafkaVersions(List<String> kafkaVersions) { this.kafkaVersions = kafkaVersions; }

    public ConfigurationState getState() { return state; }
    public void setState(ConfigurationState state) { this.state = state; }

    public Instant getCreationTime() { return creationTime; }
    public void setCreationTime(Instant creationTime) { this.creationTime = creationTime; }

    // Derived from revisions, not its own stored field, so it can never drift out of sync.
    // Must stay @JsonIgnore: writing it back out would re-create the pre-revision-history shape
    // this class now migrates away from, so a file saved today would load as a legacy entry
    // tomorrow. Only the serialization half is off - @JsonSetter("latestRevision") on
    // setLegacyLatestRevision below keeps reading the old key working.
    @JsonIgnore
    public ConfigurationRevision getLatestRevision() {
        return revisions.isEmpty() ? null : revisions.get(revisions.size() - 1);
    }

    // Read-only views: callers must go through addRevision() to mutate, so this class controls
    // publish order (server properties land before the revision that references them becomes
    // visible) rather than letting a caller append to a live list a reader can observe mid-update.
    public List<ConfigurationRevision> getRevisions() { return List.copyOf(revisions); }
    public void setRevisions(List<ConfigurationRevision> revisions) {
        this.revisions = revisions != null ? new CopyOnWriteArrayList<>(revisions) : new CopyOnWriteArrayList<>();
    }

    public Map<Long, String> getServerPropertiesByRevision() { return Map.copyOf(serverPropertiesByRevision); }
    public void setServerPropertiesByRevision(Map<Long, String> serverPropertiesByRevision) {
        this.serverPropertiesByRevision = serverPropertiesByRevision != null
                ? new ConcurrentHashMap<>(serverPropertiesByRevision) : new ConcurrentHashMap<>();
    }

    // Server properties are stored before the revision that references them is appended, so a
    // reader who isn't holding the caller's update lock can never observe a revision whose
    // properties aren't there yet (see MskService#updateConfiguration).
    public void addRevision(ConfigurationRevision revision, String serverProperties) {
        this.serverPropertiesByRevision.put(revision.getRevision(), serverProperties);
        this.revisions.add(revision);
    }

    // ── Pre-revision-history schema migration ────────────────────────────────────────────

    // The old schema's two halves arrive as separate setter calls, in whatever order the keys
    // happen to sit in the file, so neither can assemble the revision alone. These buffer what
    // has been seen so far. Deliberately un-annotated and private: Jackson's default field
    // visibility is PUBLIC_ONLY, so they stay invisible to serialization on their own, and an
    // explicit @JsonIgnore here would risk splitting the logical property the renamed setters
    // below belong to.
    private String legacyServerProperties;
    private Long legacyRevisionNumber;

    // Deserialize-only - getLatestRevision() is @JsonIgnore, so this key is never written back.
    // Ignored when revisions is already populated: a current-schema file sets "revisions"
    // directly and never writes "latestRevision", but this way the two can't fight if one ever
    // did carry both, whichever order they load in.
    @JsonSetter("latestRevision")
    public void setLegacyLatestRevision(ConfigurationRevision latestRevision) {
        if (latestRevision == null || !revisions.isEmpty()) {
            return;
        }
        // Empty string rather than null when the old entry carried no serverProperties:
        // serverPropertiesByRevision is a ConcurrentHashMap and would reject a null value, and
        // DescribeConfigurationRevision base64-encodes whatever it finds without a null check.
        addRevision(latestRevision, legacyServerProperties != null ? legacyServerProperties : "");
        this.legacyRevisionNumber = latestRevision.getRevision();
    }

    // Same deserialize-only treatment. Back-fills rather than assuming it runs first, since it
    // may arrive after setLegacyLatestRevision has already seeded the revision with the empty
    // placeholder above. Keyed off legacyRevisionNumber so a current-schema entry that somehow
    // carried a stray top-level "serverProperties" is left alone.
    @JsonSetter("serverProperties")
    public void setLegacyServerProperties(String serverProperties) {
        this.legacyServerProperties = serverProperties;
        if (serverProperties != null && legacyRevisionNumber != null) {
            this.serverPropertiesByRevision.put(legacyRevisionNumber, serverProperties);
        }
    }

    public String getAccountId() { return accountId; }
    public void setAccountId(String accountId) { this.accountId = accountId; }
}
