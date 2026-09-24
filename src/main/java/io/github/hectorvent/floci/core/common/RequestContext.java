package io.github.hectorvent.floci.core.common;

import jakarta.enterprise.context.RequestScoped;

/**
 * Holds per-request derived values, account ID, region and partition, extracted from the
 * incoming AWS credential and Authorization header. Populated by
 * {@link AccountContextFilter} before any handler runs.
 */
@RequestScoped
public class RequestContext {

    private String accountId;
    private String region;
    private String partition;

    public String getAccountId() {
        return accountId;
    }

    public void setAccountId(String accountId) {
        this.accountId = accountId;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    /**
     * The partition the request's signing region belongs to ({@code aws}, {@code aws-cn}, ...),
     * or null when nothing set it, in which case {@link RegionResolver#getPartition()} answers
     * with the deployment's partition.
     */
    public String getPartition() {
        return partition;
    }

    public void setPartition(String partition) {
        this.partition = partition;
    }
}
