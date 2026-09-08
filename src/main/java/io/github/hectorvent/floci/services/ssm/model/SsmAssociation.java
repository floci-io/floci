package io.github.hectorvent.floci.services.ssm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class SsmAssociation {

    @JsonProperty("AssociationId")
    private String associationId;

    @JsonProperty("AssociationName")
    private String associationName;

    @JsonProperty("Name")
    private String name;

    @JsonProperty("DocumentVersion")
    private String documentVersion;

    @JsonProperty("InstanceId")
    private String instanceId;

    @JsonProperty("Targets")
    private List<Target> targets;

    @JsonProperty("Parameters")
    private Map<String, List<String>> parameters;

    @JsonProperty("ScheduleExpression")
    private String scheduleExpression;

    @JsonProperty("Status")
    private AssociationStatus status;

    @JsonProperty("Overview")
    private AssociationOverview overview;

    @JsonProperty("Date")
    private Instant createdDate;

    @JsonProperty("LastExecutionDate")
    private Instant lastExecutionDate;

    @JsonProperty("ComplianceSeverity")
    private String complianceSeverity;

    @JsonProperty("MaxConcurrency")
    private String maxConcurrency;

    @JsonProperty("MaxErrors")
    private String maxErrors;

    @JsonProperty("AssociationVersion")
    private String associationVersion;

    public SsmAssociation() {}

    public SsmAssociation(
            String associationId,
            String associationName,
            String name,
            String documentVersion,
            String instanceId,
            List<Target> targets,
            Map<String, List<String>> parameters,
            String scheduleExpression,
            AssociationStatus status,
            AssociationOverview overview,
            Instant createdDate,
            Instant lastExecutionDate,
            String complianceSeverity,
            String maxConcurrency,
            String maxErrors) {
        this.associationId = associationId;
        this.associationName = associationName;
        this.name = name;
        this.documentVersion = documentVersion;
        this.instanceId = instanceId;
        this.targets = targets;
        this.parameters = parameters;
        this.scheduleExpression = scheduleExpression;
        this.status = status;
        this.overview = overview;
        this.createdDate = createdDate;
        this.lastExecutionDate = lastExecutionDate;
        this.complianceSeverity = complianceSeverity;
        this.maxConcurrency = maxConcurrency;
        this.maxErrors = maxErrors;
    }

    public String getAssociationId() {
        return associationId;
    }

    public void setAssociationId(String associationId) {
        this.associationId = associationId;
    }

    public String getAssociationName() {
        return associationName;
    }

    public void setAssociationName(String associationName) {
        this.associationName = associationName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDocumentVersion() {
        return documentVersion;
    }

    public void setDocumentVersion(String documentVersion) {
        this.documentVersion = documentVersion;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(String instanceId) {
        this.instanceId = instanceId;
    }

    public List<Target> getTargets() {
        return targets;
    }

    public void setTargets(List<Target> targets) {
        this.targets = targets;
    }

    public Map<String, List<String>> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, List<String>> parameters) {
        this.parameters = parameters;
    }

    public String getScheduleExpression() {
        return scheduleExpression;
    }

    public void setScheduleExpression(String scheduleExpression) {
        this.scheduleExpression = scheduleExpression;
    }

    public AssociationStatus getStatus() {
        return status;
    }

    public void setStatus(AssociationStatus status) {
        this.status = status;
    }

    public AssociationOverview getOverview() {
        return overview;
    }

    public void setOverview(AssociationOverview overview) {
        this.overview = overview;
    }

    public Instant getCreatedDate() {
        return createdDate;
    }

    public void setCreatedDate(Instant createdDate) {
        this.createdDate = createdDate;
    }

    public Instant getLastExecutionDate() {
        return lastExecutionDate;
    }

    public void setLastExecutionDate(Instant lastExecutionDate) {
        this.lastExecutionDate = lastExecutionDate;
    }

    public String getComplianceSeverity() {
        return complianceSeverity;
    }

    public void setComplianceSeverity(String complianceSeverity) {
        this.complianceSeverity = complianceSeverity;
    }

    public String getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(String maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public String getMaxErrors() {
        return maxErrors;
    }

    public void setMaxErrors(String maxErrors) {
        this.maxErrors = maxErrors;
    }

    public String getAssociationVersion() {
        return associationVersion;
    }

    public void setAssociationVersion(String associationVersion) {
        this.associationVersion = associationVersion;
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Target {

        @JsonProperty("Key")
        private String key;

        @JsonProperty("Values")
        private List<String> values;

        public Target() {}

        public Target(String key, List<String> values) {
            this.key = key;
            this.values = values;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public List<String> getValues() {
            return values;
        }

        public void setValues(List<String> values) {
            this.values = values;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Target target)) return false;
            return Objects.equals(key, target.key) && Objects.equals(values, target.values);
        }

        @Override
        public int hashCode() {
            return Objects.hash(key, values);
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssociationOverview {

        @JsonProperty("Status")
        private String status;

        @JsonProperty("DetailedStatus")
        private String detailedStatus;

        public AssociationOverview() {}

        public AssociationOverview(String status, String detailedStatus) {
            this.status = status;
            this.detailedStatus = detailedStatus;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getDetailedStatus() {
            return detailedStatus;
        }

        public void setDetailedStatus(String detailedStatus) {
            this.detailedStatus = detailedStatus;
        }
    }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AssociationStatus {

        @JsonProperty("Name")
        private String name;

        @JsonProperty("Message")
        private String message;

        @JsonProperty("Date")
        private Instant date;

        @JsonProperty("AdditionalInfo")
        private String additionalInfo;

        public AssociationStatus() {}

        public AssociationStatus(String name, String message, Instant date, String additionalInfo) {
            this.name = name;
            this.message = message;
            this.date = date;
            this.additionalInfo = additionalInfo;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Instant getDate() {
            return date;
        }

        public void setDate(Instant date) {
            this.date = date;
        }

        public String getAdditionalInfo() {
            return additionalInfo;
        }

        public void setAdditionalInfo(String additionalInfo) {
            this.additionalInfo = additionalInfo;
        }
    }
}
