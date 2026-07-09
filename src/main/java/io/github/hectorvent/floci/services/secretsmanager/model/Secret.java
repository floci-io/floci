package io.github.hectorvent.floci.services.secretsmanager.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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

    public void setVersions(Map<String, SecretVersion> versions) {
        this.versions = versions;
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
}
