package io.github.hectorvent.floci.services.secretsmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Secret {

    private String name;
    private String arn;
    private String description;
    private String kmsKeyId;
    private boolean rotationEnabled;
    private Instant createdDate;
    private Instant lastChangedDate;
    private Instant lastAccessedDate;
    private Instant deletedDate;
    private List<Tag> tags;
    private Map<String, SecretVersion> versions;
    private String currentVersionId;
    private String rotationLambdaArn;
    private RotationRules rotationRules;
    private Instant lastRotatedDate;
    private Instant nextRotationDate;
    private String targetAttachmentOwner;
    /** The AWS service that owns this secret and rotates it itself, such as {@code rds}. */
    private String owningService;
    /** Resource-based policy JSON attached via PutResourcePolicy, or null when none is attached. */
    private String resourcePolicy;
    /** Replicas of this secret in other regions. Only a primary carries these. */
    private List<ReplicaStatus> replicationStatus;
    /**
     * The region holding the primary this secret replicates, or null when this secret IS the
     * primary. Doubles as the "am I a replica?" flag, so a replica can never be mistaken for a
     * standalone secret and silently accept writes.
     */
    private String primaryRegion;

    /** One replica's location and health, as reported in {@code ReplicationStatus}. */
    @RegisterForReflection
    public record ReplicaStatus(
            @JsonProperty("Region") String region,
            @JsonProperty("KmsKeyId") String kmsKeyId,
            @JsonProperty("Status") String status,
            @JsonProperty("StatusMessage") String statusMessage,
            @JsonProperty("LastAccessedDate") Instant lastAccessedDate) {
    }

    @RegisterForReflection
    public record RotationRules(
            @JsonProperty("AutomaticallyAfterDays") Integer automaticallyAfterDays,
            @JsonProperty("Duration") String duration,
            @JsonProperty("ScheduleExpression") String scheduleExpression) {
    }

    public Secret() {
    }

    @RegisterForReflection
    public record Tag(
            @JsonProperty("Key") String key,
            @JsonProperty("Value") String value) {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getKmsKeyId() {
        return kmsKeyId;
    }

    public void setKmsKeyId(String kmsKeyId) {
        this.kmsKeyId = kmsKeyId;
    }

    public boolean isRotationEnabled() {
        return rotationEnabled;
    }

    public void setRotationEnabled(boolean rotationEnabled) {
        this.rotationEnabled = rotationEnabled;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getLastChangedDate() {
        return lastChangedDate;
    }

    public void setLastChangedDate(Instant lastChangedDate) {
        this.lastChangedDate = lastChangedDate;
    }

    public Instant getLastAccessedDate() {
        return lastAccessedDate;
    }

    public void setLastAccessedDate(Instant lastAccessedDate) {
        this.lastAccessedDate = lastAccessedDate;
    }

    public Instant getDeletedDate() {
        return deletedDate;
    }

    public void setDeletedDate(Instant deletedDate) {
        this.deletedDate = deletedDate;
    }

    public List<Tag> getTags() {
        return tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public Map<String, SecretVersion> getVersions() {
        return versions;
    }

    /**
     * A rotation adds versions from the rotation executor while request threads iterate this map
     * to answer DescribeSecret, GetSecretValue and ListSecretVersionIds, and those reads do not
     * take the rotation lock (a describe never waits on a rotation, as on AWS). The map must
     * therefore never be fail-fast: a plain map, which is what Jackson and most callers hand in,
     * is copied into a concurrent one; a concurrent map is installed as given.
     */
    public void setVersions(Map<String, SecretVersion> versions) {
        this.versions = versions == null || versions instanceof ConcurrentMap<String, SecretVersion>
                ? versions
                : new ConcurrentHashMap<>(versions);
    }

    public String getCurrentVersionId() {
        return currentVersionId;
    }

    public void setCurrentVersionId(String currentVersionId) {
        this.currentVersionId = currentVersionId;
    }

    public String getRotationLambdaArn() {
        return rotationLambdaArn;
    }

    public void setRotationLambdaArn(String rotationLambdaArn) {
        this.rotationLambdaArn = rotationLambdaArn;
    }

    public RotationRules getRotationRules() {
        return rotationRules;
    }

    public void setRotationRules(RotationRules rotationRules) {
        this.rotationRules = rotationRules;
    }

    public Instant getLastRotatedDate() {
        return lastRotatedDate;
    }

    public void setLastRotatedDate(Instant lastRotatedDate) {
        this.lastRotatedDate = lastRotatedDate;
    }

    public Instant getNextRotationDate() {
        return nextRotationDate;
    }

    public void setNextRotationDate(Instant nextRotationDate) {
        this.nextRotationDate = nextRotationDate;
    }

    public String getTargetAttachmentOwner() {
        return targetAttachmentOwner;
    }

    public void setTargetAttachmentOwner(String targetAttachmentOwner) {
        this.targetAttachmentOwner = targetAttachmentOwner;
    }

    public String getOwningService() {
        return owningService;
    }

    public void setOwningService(String owningService) {
        this.owningService = owningService;
    }

    public String getResourcePolicy() {
        return resourcePolicy;
    }

    public void setResourcePolicy(String resourcePolicy) {
        this.resourcePolicy = resourcePolicy;
    }

    public List<ReplicaStatus> getReplicationStatus() {
        return replicationStatus;
    }

    public void setReplicationStatus(List<ReplicaStatus> replicationStatus) {
        this.replicationStatus = replicationStatus;
    }

    public String getPrimaryRegion() {
        return primaryRegion;
    }

    public void setPrimaryRegion(String primaryRegion) {
        this.primaryRegion = primaryRegion;
    }

    public boolean isReplica() {
        return primaryRegion != null;
    }
}
