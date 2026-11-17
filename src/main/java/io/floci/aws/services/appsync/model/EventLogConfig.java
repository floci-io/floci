package io.floci.aws.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** An Events API's {@code eventConfig.logConfig}. Stored and returned; no logs are delivered. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventLogConfig {
    private String logLevel;
    private String cloudWatchLogsRoleArn;

    public String getLogLevel() {
        return logLevel;
    }

    public void setLogLevel(String logLevel) {
        this.logLevel = logLevel;
    }

    public String getCloudWatchLogsRoleArn() {
        return cloudWatchLogsRoleArn;
    }

    public void setCloudWatchLogsRoleArn(String cloudWatchLogsRoleArn) {
        this.cloudWatchLogsRoleArn = cloudWatchLogsRoleArn;
    }
}
