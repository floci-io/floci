package io.github.hectorvent.floci.services.cloudwatch.metrics.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A Contributor Insights rule. Floci stores the rule definition and its state; no log data is
 * ever aggregated, so {@code GetInsightRuleReport} is deliberately not implemented.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class InsightRule {

    public static final String STATE_ENABLED = "ENABLED";
    public static final String STATE_DISABLED = "DISABLED";

    private String name;
    private String arn;
    private String state = STATE_ENABLED;
    private String schema;
    private String definition;
    private boolean managedRule;
    private boolean applyOnTransformedLogs;
    private Map<String, String> tags = new LinkedHashMap<>();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }

    public String getDefinition() { return definition; }
    public void setDefinition(String definition) { this.definition = definition; }

    public boolean isManagedRule() { return managedRule; }
    public void setManagedRule(boolean managedRule) { this.managedRule = managedRule; }

    public boolean isApplyOnTransformedLogs() { return applyOnTransformedLogs; }
    public void setApplyOnTransformedLogs(boolean applyOnTransformedLogs) {
        this.applyOnTransformedLogs = applyOnTransformedLogs;
    }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }
}
