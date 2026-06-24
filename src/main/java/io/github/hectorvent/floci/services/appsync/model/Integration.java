package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Integration {
    private String dataSourceName;
    private LambdaConfig lambdaConfig;

    public String getDataSourceName() { return dataSourceName; }
    public void setDataSourceName(String dataSourceName) { this.dataSourceName = dataSourceName; }

    public LambdaConfig getLambdaConfig() { return lambdaConfig; }
    public void setLambdaConfig(LambdaConfig lambdaConfig) { this.lambdaConfig = lambdaConfig; }
}
