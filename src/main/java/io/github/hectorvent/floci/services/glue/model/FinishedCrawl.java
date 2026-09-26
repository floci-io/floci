package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;

/** One finished crawl in a crawler's recent history; Floci bookkeeping, never sent on the wire. */
@RegisterForReflection
// Earlier versions stored the chain's run count here as triggeredRuns: it is still read, so a chain in
// flight across an upgrade keeps its count, but never written back.
@JsonIgnoreProperties(ignoreUnknown = true)
public class FinishedCrawl {
    private long sequence;
    private Instant startedAt;
    private Instant finishedAt;
    private String status;
    private String crawlId;
    private String originRunId;

    @JsonProperty(value = "triggeredRuns", access = JsonProperty.Access.WRITE_ONLY)
    private int legacyTriggeredRuns;

    public FinishedCrawl() {}

    public int getLegacyTriggeredRuns() { return legacyTriggeredRuns; }
    public void setLegacyTriggeredRuns(int legacyTriggeredRuns) { this.legacyTriggeredRuns = legacyTriggeredRuns; }

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

}
