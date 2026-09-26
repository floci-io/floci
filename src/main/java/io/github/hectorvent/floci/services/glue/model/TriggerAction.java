package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/** One action of a trigger: start a job run or a crawl. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TriggerAction {
    @JsonProperty("JobName")
    private String jobName;

    @JsonProperty("Arguments")
    private Map<String, String> arguments;

    @JsonProperty("Timeout")
    private Integer timeout;

    @JsonProperty("SecurityConfiguration")
    private String securityConfiguration;

    @JsonProperty("NotificationProperty")
    private NotificationProperty notificationProperty;

    @JsonProperty("CrawlerName")
    private String crawlerName;

    public TriggerAction() {}

    public String getJobName() { return jobName; }
    public void setJobName(String jobName) { this.jobName = jobName; }

    public Map<String, String> getArguments() { return arguments; }
    public void setArguments(Map<String, String> arguments) { this.arguments = arguments; }

    public Integer getTimeout() { return timeout; }
    public void setTimeout(Integer timeout) { this.timeout = timeout; }

    public String getSecurityConfiguration() { return securityConfiguration; }
    public void setSecurityConfiguration(String securityConfiguration) { this.securityConfiguration = securityConfiguration; }

    public NotificationProperty getNotificationProperty() { return notificationProperty; }
    public void setNotificationProperty(NotificationProperty notificationProperty) { this.notificationProperty = notificationProperty; }

    public String getCrawlerName() { return crawlerName; }
    public void setCrawlerName(String crawlerName) { this.crawlerName = crawlerName; }
}
