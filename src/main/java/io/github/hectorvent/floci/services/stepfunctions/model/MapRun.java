package io.github.hectorvent.floci.services.stepfunctions.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/**
 * A distributed Map run that has finished, keyed by its Map run ARN.
 *
 * <p>A Map fails on the first item that fails, so a run only reaches the {@code ResultWriter} that
 * mints its ARN once every item has succeeded. That is why a run carries a single {@code itemCount}
 * rather than the ten counters {@code DescribeMapRun} reports: the other nine are zero, and
 * {@code succeeded}, {@code total} and {@code resultsWritten} are all this count.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MapRun {
    private String mapRunArn;
    /** The execution of the state machine whose Map state opened this run. */
    private String executionArn;
    private double startDate;
    private double stopDate;
    /** Items processed by the run, which is also the number of child executions it ran. */
    private int itemCount;
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

    public int getMaxConcurrency() { return maxConcurrency; }
    public void setMaxConcurrency(int maxConcurrency) { this.maxConcurrency = maxConcurrency; }
}
