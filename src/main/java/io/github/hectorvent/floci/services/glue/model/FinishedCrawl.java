package io.github.hectorvent.floci.services.glue.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** One finished crawl in a crawler's recent history; Floci bookkeeping, never sent on the wire. */
@RegisterForReflection
public class FinishedCrawl {
    private long sequence;
    private Instant startedAt;
    private Instant finishedAt;
    private String status;
    private String crawlId;
    private String originRunId;
    private int triggeredRuns;

    public FinishedCrawl() {}

    public long getSequence() { return sequence; }
    public void setSequence(long sequence) { this.sequence = sequence; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCrawlId() { return crawlId; }
    public void setCrawlId(String crawlId) { this.crawlId = crawlId; }

    public String getOriginRunId() { return originRunId; }
    public void setOriginRunId(String originRunId) { this.originRunId = originRunId; }

    public int getTriggeredRuns() { return triggeredRuns; }
    public void setTriggeredRuns(int triggeredRuns) { this.triggeredRuns = triggeredRuns; }
}
