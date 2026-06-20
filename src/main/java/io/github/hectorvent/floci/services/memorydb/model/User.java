package io.github.hectorvent.floci.services.memorydb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A MemoryDB user. Users authenticate either with one or more passwords or with IAM,
 * and are attached to one or more {@link Acl}s. A cluster's effective authentication is
 * resolved from the users of the ACL it references.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class User {

    private String name;
    private String status;
    private AuthMode authMode;
    private List<String> passwords = new ArrayList<>();
    private String accessString;
    private String minimumEngineVersion;
    private String arn;
    private Instant createdAt;

    public User() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public AuthMode getAuthMode() { return authMode; }
    public void setAuthMode(AuthMode authMode) { this.authMode = authMode; }

    public List<String> getPasswords() { return passwords; }
    public void setPasswords(List<String> passwords) {
        this.passwords = passwords != null ? passwords : new ArrayList<>();
    }

    public String getAccessString() { return accessString; }
    public void setAccessString(String accessString) { this.accessString = accessString; }

    public String getMinimumEngineVersion() { return minimumEngineVersion; }
    public void setMinimumEngineVersion(String minimumEngineVersion) {
        this.minimumEngineVersion = minimumEngineVersion;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
