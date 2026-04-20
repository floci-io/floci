package io.github.hectorvent.floci.services.pipes.model;

public enum PipeState {
    RUNNING,
    STOPPED,
    CREATING,
    UPDATING,
    DELETING,
    STARTING,
    STOPPING,
    CREATE_FAILED,
    UPDATE_FAILED,
    START_FAILED,
    STOP_FAILED,
    DELETE_FAILED
}
