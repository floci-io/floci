package io.github.hectorvent.floci.services.dynamodb.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * The CloudWatch Contributor Insights setting of a table or a global secondary index, kept from the
 * first UpdateContributorInsights call on it. {@code rulesCreatedAt} stamps the rule names and is
 * reset each time the setting goes from disabled to enabled, when AWS creates the rules again.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ContributorInsights {

    private boolean enabled;
    private String mode;
    private Instant lastUpdateDateTime;
    private Instant rulesCreatedAt;

    public ContributorInsights() {
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public Instant getLastUpdateDateTime() { return lastUpdateDateTime; }
    public void setLastUpdateDateTime(Instant lastUpdateDateTime) { this.lastUpdateDateTime = lastUpdateDateTime; }

    public Instant getRulesCreatedAt() { return rulesCreatedAt; }
    public void setRulesCreatedAt(Instant rulesCreatedAt) { this.rulesCreatedAt = rulesCreatedAt; }
}
