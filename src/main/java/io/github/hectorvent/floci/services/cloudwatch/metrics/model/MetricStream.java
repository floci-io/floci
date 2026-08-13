package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A CloudWatch metric stream. Floci stores the stream definition and reports its state;
 * no metric data is ever delivered to the configured Firehose.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetricStream {

    public static final String STATE_RUNNING = "running";
    public static final String STATE_STOPPED = "stopped";

    private String name;
    private String arn;
    private String firehoseArn;
    private String roleArn;
    private String outputFormat;
    private String state = STATE_RUNNING;
    private long creationDate;
    private long lastUpdateDate;
    private boolean includeLinkedAccountsMetrics;
    private List<MetricStreamFilter> includeFilters = new ArrayList<>();
    private List<MetricStreamFilter> excludeFilters = new ArrayList<>();
    private List<MetricStreamStatisticsConfiguration> statisticsConfigurations = new ArrayList<>();
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getFirehoseArn() { return firehoseArn; }
    public void setFirehoseArn(String firehoseArn) { this.firehoseArn = firehoseArn; }

    public String getRoleArn() { return roleArn; }
    public void setRoleArn(String roleArn) { this.roleArn = roleArn; }

    public String getOutputFormat() { return outputFormat; }
    public void setOutputFormat(String outputFormat) { this.outputFormat = outputFormat; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public long getCreationDate() { return creationDate; }
    public void setCreationDate(long creationDate) { this.creationDate = creationDate; }

    public long getLastUpdateDate() { return lastUpdateDate; }
    public void setLastUpdateDate(long lastUpdateDate) { this.lastUpdateDate = lastUpdateDate; }

    public boolean isIncludeLinkedAccountsMetrics() { return includeLinkedAccountsMetrics; }
    public void setIncludeLinkedAccountsMetrics(boolean includeLinkedAccountsMetrics) {
        this.includeLinkedAccountsMetrics = includeLinkedAccountsMetrics;
    }

    public List<MetricStreamFilter> getIncludeFilters() { return includeFilters; }
    public void setIncludeFilters(List<MetricStreamFilter> includeFilters) {
        this.includeFilters = includeFilters != null ? includeFilters : new ArrayList<>();
    }

    public List<MetricStreamFilter> getExcludeFilters() { return excludeFilters; }
    public void setExcludeFilters(List<MetricStreamFilter> excludeFilters) {
        this.excludeFilters = excludeFilters != null ? excludeFilters : new ArrayList<>();
    }

    public List<MetricStreamStatisticsConfiguration> getStatisticsConfigurations() { return statisticsConfigurations; }
    public void setStatisticsConfigurations(List<MetricStreamStatisticsConfiguration> statisticsConfigurations) {
        this.statisticsConfigurations = statisticsConfigurations != null ? statisticsConfigurations : new ArrayList<>();
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) {
        this.tags = tags != null ? tags : new LinkedHashMap<>();
    }
}
