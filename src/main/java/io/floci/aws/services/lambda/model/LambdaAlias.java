package io.floci.aws.services.lambda.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LambdaAlias {

    private String name;
    private String functionName;
    private String functionVersion;
    private String description;
    private String aliasArn;
    private long createdDate;
    private long lastModifiedDate;
    private String revisionId;
    private LambdaUrlConfig urlConfig;
    private Map<String, Double> routingConfig;

    public LambdaAlias() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }

    public String getFunctionVersion() { return functionVersion; }
    public void setFunctionVersion(String functionVersion) { this.functionVersion = functionVersion; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getAliasArn() { return aliasArn; }
    public void setAliasArn(String aliasArn) { this.aliasArn = aliasArn; }

    public long getCreatedDate() { return createdDate; }
    public void setCreatedDate(long createdDate) { this.createdDate = createdDate; }

    public long getLastModifiedDate() { return lastModifiedDate; }
    public void setLastModifiedDate(long lastModifiedDate) { this.lastModifiedDate = lastModifiedDate; }

    public String getRevisionId() { return revisionId; }
    public void setRevisionId(String revisionId) { this.revisionId = revisionId; }

    public LambdaUrlConfig getUrlConfig() { return urlConfig; }
    public void setUrlConfig(LambdaUrlConfig urlConfig) { this.urlConfig = urlConfig; }

    public Map<String, Double> getRoutingConfig() { return routingConfig; }
    public void setRoutingConfig(Map<String, Double> routingConfig) { this.routingConfig = routingConfig; }
}
