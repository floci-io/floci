package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A registry's own settings, one record per account and Region: the registry policy, the
 * replication configuration and the scanning configuration. Floci records them and returns them;
 * it does not replicate or scan images.
 */
@RegisterForReflection
public class RegistrySettings {

    /** One repository filter: a prefix ({@code PREFIX_MATCH}) or a wildcard ({@code WILDCARD}). */
    @RegisterForReflection
    public record RepositoryFilter(String filter, String filterType) {}

    /** One replication destination. */
    @RegisterForReflection
    public record ReplicationDestination(String region, String registryId) {}

    /** One replication rule: where to copy, and which repositories. */
    @RegisterForReflection
    public record ReplicationRule(List<ReplicationDestination> destinations, List<RepositoryFilter> repositoryFilters) {}

    /** One scanning rule: how often, and which repositories. */
    @RegisterForReflection
    public record ScanningRule(String scanFrequency, List<RepositoryFilter> repositoryFilters) {}

    /** An excluded principal and the time its registration was created. */
    @RegisterForReflection
    public record PullTimeUpdateExclusion(String principalArn, Instant createdAt) {}

    private List<PullTimeUpdateExclusion> pullTimeUpdateExclusions;
    private String policyText;
    private List<ReplicationRule> replicationRules;
    private String scanType;
    private List<ScanningRule> scanningRules;
    private Map<String, String> accountSettings;

    public RegistrySettings() {}

    public List<PullTimeUpdateExclusion> getPullTimeUpdateExclusions() { return pullTimeUpdateExclusions; }
    public void setPullTimeUpdateExclusions(List<PullTimeUpdateExclusion> exclusions) { this.pullTimeUpdateExclusions = exclusions; }

    public String getPolicyText() { return policyText; }
    public void setPolicyText(String policyText) { this.policyText = policyText; }

    public List<ReplicationRule> getReplicationRules() { return replicationRules; }
    public void setReplicationRules(List<ReplicationRule> replicationRules) { this.replicationRules = replicationRules; }

    public String getScanType() { return scanType; }
    public void setScanType(String scanType) { this.scanType = scanType; }

    public List<ScanningRule> getScanningRules() { return scanningRules; }
    public void setScanningRules(List<ScanningRule> scanningRules) { this.scanningRules = scanningRules; }

    public Map<String, String> getAccountSettings() { return accountSettings; }
    public void setAccountSettings(Map<String, String> accountSettings) { this.accountSettings = accountSettings; }
}
