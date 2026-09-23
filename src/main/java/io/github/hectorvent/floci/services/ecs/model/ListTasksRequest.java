package io.github.hectorvent.floci.services.ecs.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A parsed {@code ListTasks} request.
 *
 * <p>Every member narrows the listing. {@code startedBy} is the exception: ECS requires it to be
 * used on its own, which {@link #hasOtherFilters()} is there to check. {@code cluster} is not a
 * filter for that rule, it is the scope the listing runs in, so it pairs with {@code startedBy}.
 */
@RegisterForReflection
public class ListTasksRequest {

    private String cluster;
    private String containerInstance;
    private String desiredStatus;
    private String family;
    private LaunchType launchType;
    private String serviceName;
    private String startedBy;
    private Integer maxResults;
    private String nextToken;

    public String getCluster() { return cluster; }
    public void setCluster(String cluster) { this.cluster = cluster; }

    public String getContainerInstance() { return containerInstance; }
    public void setContainerInstance(String containerInstance) { this.containerInstance = containerInstance; }

    public String getDesiredStatus() { return desiredStatus; }
    public void setDesiredStatus(String desiredStatus) { this.desiredStatus = desiredStatus; }

    public String getFamily() { return family; }
    public void setFamily(String family) { this.family = family; }

    public LaunchType getLaunchType() { return launchType; }
    public void setLaunchType(LaunchType launchType) { this.launchType = launchType; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getStartedBy() { return startedBy; }
    public void setStartedBy(String startedBy) { this.startedBy = startedBy; }

    public Integer getMaxResults() { return maxResults; }
    public void setMaxResults(Integer maxResults) { this.maxResults = maxResults; }

    public String getNextToken() { return nextToken; }
    public void setNextToken(String nextToken) { this.nextToken = nextToken; }

    /**
     * Whether any filter other than {@code startedBy} is set. The cluster and the pagination
     * members do not count: the cluster scopes the listing rather than narrowing it, and AWS
     * accepts {@code list-tasks --cluster X --started-by Y}.
     */
    public boolean hasOtherFilters() {
        return containerInstance != null || desiredStatus != null
                || family != null || launchType != null || serviceName != null;
    }
}
