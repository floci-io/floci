package io.github.hectorvent.floci.services.glue.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/**
 * Floci's own bookkeeping, never sent on the wire: how many runs triggers have started on behalf of one
 * chain origin, and when that count last changed (the least recently used chains are forgotten first).
 */
@RegisterForReflection
public class TriggerChainBudget {
    private int triggeredRuns;
    private Instant lastUsed;

    public TriggerChainBudget() {}

    public int getTriggeredRuns() { return triggeredRuns; }
    public void setTriggeredRuns(int triggeredRuns) { this.triggeredRuns = triggeredRuns; }

    public Instant getLastUsed() { return lastUsed; }
    public void setLastUsed(Instant lastUsed) { this.lastUsed = lastUsed; }
}
