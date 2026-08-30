package io.github.hectorvent.floci.services.ecs.container;

import java.io.Closeable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Holds the runtime Docker container IDs for a running ECS task.
 * Maps container names to Docker IDs and Docker IDs to their log stream handles.
 */
public class EcsTaskHandle {

    private final String taskArn;
    private final Map<String, String> containerIds;   // containerName → dockerId
    private final Map<String, Closeable> logStreamsByContainerId;

    public EcsTaskHandle(String taskArn, Map<String, String> containerIds,
                         Map<String, Closeable> logStreamsByContainerId) {
        this.taskArn = taskArn;
        this.containerIds = new LinkedHashMap<>(containerIds);
        this.logStreamsByContainerId = new LinkedHashMap<>(logStreamsByContainerId);
    }

    public String getTaskArn() { return taskArn; }
    public Map<String, String> getContainerIds() { return containerIds; }
    public Map<String, Closeable> getLogStreamsByContainerId() { return logStreamsByContainerId; }

    /** Removes and returns the log stream that no longer needs task-level ownership. */
    public Closeable removeLogStream(String containerId) {
        return logStreamsByContainerId.remove(containerId);
    }

    public boolean hasOpenLogStreams() {
        return !logStreamsByContainerId.isEmpty();
    }
}
