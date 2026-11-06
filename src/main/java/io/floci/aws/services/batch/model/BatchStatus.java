package io.floci.aws.services.batch.model;

public enum BatchStatus {
    SUBMITTED,
    PENDING,
    RUNNABLE,
    STARTING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
