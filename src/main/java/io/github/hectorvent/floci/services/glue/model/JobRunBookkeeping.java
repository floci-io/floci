package io.github.hectorvent.floci.services.glue.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Floci's own bookkeeping for one job run, never sent on the wire, and deleted with the run: the order
 * in which Floci saw it finish, the run that set off its trigger chain, when the run is itself such
 * an origin how many runs triggers have started on its behalf, and the workflow run it belongs to.
 */
@RegisterForReflection
public class JobRunBookkeeping {
    private long completionOrder;
    private String originRunId;
    private int triggeredRuns;
    private String workflowRunId;

    public JobRunBookkeeping() {}

    public long getCompletionOrder() { return completionOrder; }
    public void setCompletionOrder(long completionOrder) { this.completionOrder = completionOrder; }

    public String getOriginRunId() { return originRunId; }
    public void setOriginRunId(String originRunId) { this.originRunId = originRunId; }

    public int getTriggeredRuns() { return triggeredRuns; }
    public void setTriggeredRuns(int triggeredRuns) { this.triggeredRuns = triggeredRuns; }

    public String getWorkflowRunId() { return workflowRunId; }
    public void setWorkflowRunId(String workflowRunId) { this.workflowRunId = workflowRunId; }
}
