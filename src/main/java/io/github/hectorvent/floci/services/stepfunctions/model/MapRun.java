package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A distributed Map run that has finished, keyed by its Map run ARN. It is kept on success and on
 * failure, so the {@code mapRunArn} of a {@code MapRunStarted} event resolves through
 * {@code DescribeMapRun}. A Map cancels the remaining items on the first failure, so a failed run
 * reports those as aborted.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapRun {
    private String mapRunArn;
    private String status;
    /** The execution of the state machine whose Map state opened this run. */
    private String executionArn;
    private double startDate;
    private double stopDate;
    /** Items of the run, which is also the number of child executions it ran. */
    private int itemCount;
    private int succeededCount;
    private int failedCount;
    /** The Map's declared MaxConcurrency, with an unbounded Map held as Integer.MAX_VALUE. */
    private int maxConcurrency;

    public String getMapRunArn() { return mapRunArn; }
    public void setMapRunArn(String mapRunArn) { this.mapRunArn = mapRunArn; }

    public String getExecutionArn() { return executionArn; }
    public void setExecutionArn(String executionArn) { this.executionArn = executionArn; }

    public double getStartDate() { return startDate; }
    public void setStartDate(double startDate) { this.startDate = startDate; }

    public double getStopDate() { return stopDate; }
    public void setStopDate(double stopDate) { this.stopDate = stopDate; }

    public int getItemCount() { return itemCount; }
    public void setItemCount(int itemCount) { this.itemCount = itemCount; }
    /** A run recorded before the status was kept is one whose every item succeeded. */
    public String getStatus() { return status == null ? "SUCCEEDED" : status; }
    public void setStatus(String status) { this.status = status; }
    public int getSucceededCount() { return status == null ? itemCount : succeededCount; }
    public void setSucceededCount(int succeededCount) { this.succeededCount = succeededCount; }
    public int getFailedCount() { return failedCount; }
    public void setFailedCount(int failedCount) { this.failedCount = failedCount; }

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
}
