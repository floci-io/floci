package io.github.hectorvent.floci.services.ecr.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

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

    private String policyText;
    private List<ReplicationRule> replicationRules;
    private String scanType;
    private List<ScanningRule> scanningRules;

    public RegistrySettings() {}

    public String getPolicyText() { return policyText; }
    public void setPolicyText(String policyText) { this.policyText = policyText; }

    public List<ReplicationRule> getReplicationRules() { return replicationRules; }
    public void setReplicationRules(List<ReplicationRule> replicationRules) { this.replicationRules = replicationRules; }

    public String getScanType() { return scanType; }
    public void setScanType(String scanType) { this.scanType = scanType; }

    public List<ScanningRule> getScanningRules() { return scanningRules; }
    public void setScanningRules(List<ScanningRule> scanningRules) { this.scanningRules = scanningRules; }
}
