package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** One condition of a trigger predicate: a job run state or a crawl state. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TriggerCondition {
    @JsonProperty("LogicalOperator")
    private String logicalOperator;

    @JsonProperty("JobName")
    private String jobName;

    @JsonProperty("State")
    private String state;

    @JsonProperty("CrawlerName")
    private String crawlerName;

    @JsonProperty("CrawlState")
    private String crawlState;

    public TriggerCondition() {}

    public String getLogicalOperator() { return logicalOperator; }
    public void setLogicalOperator(String logicalOperator) { this.logicalOperator = logicalOperator; }

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getCrawlerName() { return crawlerName; }
    public void setCrawlerName(String crawlerName) { this.crawlerName = crawlerName; }

    public String getCrawlState() { return crawlState; }
    public void setCrawlState(String crawlState) { this.crawlState = crawlState; }
}
