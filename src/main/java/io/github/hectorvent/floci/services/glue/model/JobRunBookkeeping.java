package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * Floci's own bookkeeping for one job run, never sent on the wire, and deleted with the run: the order
 * in which Floci saw it finish, and the run that set off its trigger chain.
 */
@RegisterForReflection
// Earlier versions stored the chain's run count here as triggeredRuns: it is still read, so a chain in
// flight across an upgrade keeps its count, but never written back.
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobRunBookkeeping {
    private long completionOrder;
    private String originRunId;

    @JsonProperty(value = "triggeredRuns", access = JsonProperty.Access.WRITE_ONLY)
    private int legacyTriggeredRuns;

    public JobRunBookkeeping() {}

    public int getLegacyTriggeredRuns() { return legacyTriggeredRuns; }
    public void setLegacyTriggeredRuns(int legacyTriggeredRuns) { this.legacyTriggeredRuns = legacyTriggeredRuns; }

    public long getCompletionOrder() { return completionOrder; }
    public void setCompletionOrder(long completionOrder) { this.completionOrder = completionOrder; }

    public String getOriginRunId() { return originRunId; }
    public void setOriginRunId(String originRunId) { this.originRunId = originRunId; }

}
