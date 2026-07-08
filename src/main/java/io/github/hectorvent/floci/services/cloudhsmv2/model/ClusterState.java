package io.github.hectorvent.floci.services.cloudhsmv2.model;

/**
 * CloudHSM v2 cluster lifecycle states.
 *
 * <p>A cluster starts as {@link #UNINITIALIZED} when created, transitions to
 * {@link #INITIALIZED} after {@code InitializeCluster} succeeds, and finally
 * reaches {@link #ACTIVE} once it is initialized <em>and</em> has at least one HSM.
 */
public enum ClusterState {

    CREATE_IN_PROGRESS,
    UNINITIALIZED,
    INITIALIZE_IN_PROGRESS,
    INITIALIZED,
    ACTIVE,
    UPDATE_IN_PROGRESS,
    DELETE_IN_PROGRESS,
    DELETED,
    DEGRADED;

    /** Returns the AWS wire representation (e.g. {@code "UNINITIALIZED"}). */
    public String wireValue() {
        return name();
    }

    public static ClusterState fromWire(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ClusterState: " + value);
        }
    }
}
