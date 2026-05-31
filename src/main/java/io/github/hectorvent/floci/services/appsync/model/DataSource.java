package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataSource {
    private String name;
    private String description;
    private DataSourceType type;
    private String serviceRoleArn;
    private Map<String, Object> dynamodbConfig;
    private Map<String, Object> lambdaConfig;
    private Map<String, Object> httpConfig;
    private Map<String, Object> eventBridgeConfig;
    private Map<String, Object> relationalDatabaseConfig;
    private Map<String, Object> openSearchServiceConfig;
    private Map<String, Object> amazonBedrockRuntimeConfig;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public DataSourceType getType() { return type; }
    public void setType(DataSourceType type) { this.type = type; }

    public String getServiceRoleArn() { return serviceRoleArn; }
    public void setServiceRoleArn(String serviceRoleArn) { this.serviceRoleArn = serviceRoleArn; }

    public Map<String, Object> getDynamodbConfig() { return dynamodbConfig; }
    public void setDynamodbConfig(Map<String, Object> dynamodbConfig) { this.dynamodbConfig = dynamodbConfig; }

    public Map<String, Object> getLambdaConfig() { return lambdaConfig; }
    public void setLambdaConfig(Map<String, Object> lambdaConfig) { this.lambdaConfig = lambdaConfig; }

    public Map<String, Object> getHttpConfig() { return httpConfig; }
    public void setHttpConfig(Map<String, Object> httpConfig) { this.httpConfig = httpConfig; }

    public Map<String, Object> getEventBridgeConfig() { return eventBridgeConfig; }
    public void setEventBridgeConfig(Map<String, Object> eventBridgeConfig) { this.eventBridgeConfig = eventBridgeConfig; }

    public Map<String, Object> getRelationalDatabaseConfig() { return relationalDatabaseConfig; }
    public void setRelationalDatabaseConfig(Map<String, Object> config) { this.relationalDatabaseConfig = config; }

    public Map<String, Object> getOpenSearchServiceConfig() { return openSearchServiceConfig; }
    public void setOpenSearchServiceConfig(Map<String, Object> config) { this.openSearchServiceConfig = config; }

    public Map<String, Object> getAmazonBedrockRuntimeConfig() { return amazonBedrockRuntimeConfig; }
    public void setAmazonBedrockRuntimeConfig(Map<String, Object> config) { this.amazonBedrockRuntimeConfig = config; }
}
