package io.floci.aws.services.rolesanywhere.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A subject, as {@code SubjectDetail} reports it: one certificate identity that has asked for temporary
 * credentials, with the credentials and instance properties it presented. Only a credential request writes one,
 * so the control plane reads subjects and never creates them.
 */
@RegisterForReflection
public class Subject {

    private String subjectId;
    private String subjectArn;
    private boolean enabled;
    private String x509Subject;
    private Instant lastSeenAt;
    private Instant createdAt;
    private Instant updatedAt;
    private List<CredentialSummary> credentials = new ArrayList<>();
    private List<InstanceProperty> instanceProperties = new ArrayList<>();

    public String getSubjectId() {
        return subjectId;
    }

    public void setSubjectId(String subjectId) {
        this.subjectId = subjectId;
    }

    public String getSubjectArn() {
        return subjectArn;
    }

    public void setSubjectArn(String subjectArn) {
        this.subjectArn = subjectArn;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getX509Subject() {
        return x509Subject;
    }

    public void setX509Subject(String x509Subject) {
        this.x509Subject = x509Subject;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public void setLastSeenAt(Instant lastSeenAt) {
        this.lastSeenAt = lastSeenAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<CredentialSummary> getCredentials() {
        return credentials;
    }

    public void setCredentials(List<CredentialSummary> credentials) {
        this.credentials = credentials == null ? new ArrayList<>() : new ArrayList<>(credentials);
    }

    public List<InstanceProperty> getInstanceProperties() {
        return instanceProperties;
    }

    public void setInstanceProperties(List<InstanceProperty> instanceProperties) {
        this.instanceProperties = instanceProperties == null ? new ArrayList<>() : new ArrayList<>(instanceProperties);
    }
}
