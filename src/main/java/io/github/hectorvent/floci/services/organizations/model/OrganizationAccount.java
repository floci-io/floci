package io.github.hectorvent.floci.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain model for an account that belongs to an organization.
 *
 * <p>The account id doubles as a Floci account: {@code AccountResolver} treats any 12-digit
 * access key id as an account, so an account created here can immediately be used as a caller
 * identity against every other Floci service.
 */
@RegisterForReflection
public class OrganizationAccount {

    private String id;
    private String arn;
    private String email;
    private String name;
    private String status;
    private String joinedMethod;
    private Instant joinedTimestamp;
    private String organizationId;
    private String parentId;
    private Map<String, String> tags = new LinkedHashMap<>();

    /** Service principal to the timestamp this account was registered as a delegated admin. */
    private Map<String, Instant> delegatedServices = new LinkedHashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getArn() {
        return arn;
    }

    public void setArn(String arn) {
        this.arn = arn;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getJoinedMethod() {
        return joinedMethod;
    }

    public void setJoinedMethod(String joinedMethod) {
        this.joinedMethod = joinedMethod;
    }

    public Instant getJoinedTimestamp() {
        return joinedTimestamp;
    }

    public void setJoinedTimestamp(Instant joinedTimestamp) {
        this.joinedTimestamp = joinedTimestamp;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public Map<String, Instant> getDelegatedServices() {
        return delegatedServices;
    }

    public void setDelegatedServices(Map<String, Instant> delegatedServices) {
        this.delegatedServices = delegatedServices;
    }
}
