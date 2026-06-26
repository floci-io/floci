package io.github.hectorvent.floci.services.memorydb.model;

/**
 * MemoryDB cluster lifecycle states. The wire value is lowercase
 * (e.g. {@code available}) to match the real AWS MemoryDB API.
 */
public enum ClusterStatus {
    CREATING("creating"),
    AVAILABLE("available"),
    UPDATING("updating"),
    DELETING("deleting");

    private final String wireValue;

    ClusterStatus(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }
}
