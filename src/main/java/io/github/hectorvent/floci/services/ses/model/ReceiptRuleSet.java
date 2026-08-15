package io.github.hectorvent.floci.services.ses.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * A SES v1 receipt rule set. Floci stores it inertly: there is no inbound-mail endpoint, so a rule
 * set never holds any receipt rules and performs no mail routing. It exists only so the management
 * API (create / describe / list / delete and set/describe active) round-trips, which is enough to
 * unblock tools like Terraform that declare a rule set during bootstrap.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ReceiptRuleSet {

    @JsonProperty("Name")
    private String name;

    @JsonProperty("CreatedTimestamp")
    private Instant createdTimestamp;

    // Whether this is the account's active rule set for its region. Internal bookkeeping — AWS tracks
    // the active set separately and does not surface this flag on the rule-set object itself.
    @JsonProperty("Active")
    private boolean active;

    public ReceiptRuleSet() {
    }

    public ReceiptRuleSet(String name, Instant createdTimestamp) {
        this.name = name;
        this.createdTimestamp = createdTimestamp;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Instant getCreatedTimestamp() {
        return createdTimestamp;
    }

    public void setCreatedTimestamp(Instant createdTimestamp) {
        this.createdTimestamp = createdTimestamp;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
