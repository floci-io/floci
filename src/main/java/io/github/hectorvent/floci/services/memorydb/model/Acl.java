package io.github.hectorvent.floci.services.memorydb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A MemoryDB Access Control List (ACL): a named collection of {@link User}s that a
 * cluster references via {@code ACLName} to determine who may authenticate.
 *
 * <p>The list of clusters using an ACL is derived from the cluster store at read time
 * rather than stored here, so it never drifts out of sync.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Acl {

    private String name;
    private String status;
    private List<String> userNames = new ArrayList<>();
    private String minimumEngineVersion;
    private String arn;
    private Instant createdAt;

    public Acl() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public List<String> getUserNames() { return userNames; }
    public void setUserNames(List<String> userNames) {
        this.userNames = userNames != null ? userNames : new ArrayList<>();
    }

    public String getMinimumEngineVersion() { return minimumEngineVersion; }
    public void setMinimumEngineVersion(String minimumEngineVersion) {
        this.minimumEngineVersion = minimumEngineVersion;
    }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
