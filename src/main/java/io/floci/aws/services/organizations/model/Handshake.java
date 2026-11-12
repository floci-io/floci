package io.floci.aws.services.organizations.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Domain model for a handshake — the two-party exchange behind
 * {@code InviteAccountToOrganization} and {@code EnableAllFeatures}.
 *
 * <p>{@code targetAccountId} and {@code targetEmail} are Floci bookkeeping, not part of the
 * AWS wire shape: they record which account (or email) the invitation was addressed to so
 * {@code ListHandshakesForAccount} can answer for a caller that has not joined yet.
 * {@code tags} is bookkeeping too: the invitation's tags, attached to the account when it
 * accepts.
 */
@RegisterForReflection
public class Handshake {

    private String id;
    private String arn;
    private String state;
    private String action;
    private Instant requestedTimestamp;
    private Instant expirationTimestamp;
    private List<HandshakeParty> parties = new ArrayList<>();
    private List<HandshakeResource> resources = new ArrayList<>();
    private String organizationId;
    private String targetAccountId;
    private String targetEmail;
    private Map<String, String> tags = new LinkedHashMap<>();

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

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public Instant getRequestedTimestamp() {
        return requestedTimestamp;
    }

    public void setRequestedTimestamp(Instant requestedTimestamp) {
        this.requestedTimestamp = requestedTimestamp;
    }

    public Instant getExpirationTimestamp() {
        return expirationTimestamp;
    }

    public void setExpirationTimestamp(Instant expirationTimestamp) {
        this.expirationTimestamp = expirationTimestamp;
    }

    public List<HandshakeParty> getParties() {
        return parties;
    }

    public void setParties(List<HandshakeParty> parties) {
        this.parties = parties;
    }

    public List<HandshakeResource> getResources() {
        return resources;
    }

    public void setResources(List<HandshakeResource> resources) {
        this.resources = resources;
    }

    public String getOrganizationId() {
        return organizationId;
    }

    public void setOrganizationId(String organizationId) {
        this.organizationId = organizationId;
    }

    public String getTargetAccountId() {
        return targetAccountId;
    }

    public void setTargetAccountId(String targetAccountId) {
        this.targetAccountId = targetAccountId;
    }

    public String getTargetEmail() {
        return targetEmail;
    }

    public void setTargetEmail(String targetEmail) {
        this.targetEmail = targetEmail;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }
}
