package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FunctionConfiguration {
    private String functionId;
    private String name;
    private String description;
    private String dataSourceName;
    private String requestMappingTemplate;
    private String responseMappingTemplate;
    private String functionVersion;
    private String functionArn;
    private String code;
    private Resolver.ResolverRuntime runtime;
    private Integer maxBatchSize;

    public String getFunctionId() { return functionId; }
    public void setFunctionId(String functionId) { this.functionId = functionId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getDataSourceName() { return dataSourceName; }
    public void setDataSourceName(String dataSourceName) { this.dataSourceName = dataSourceName; }

    public String getRequestMappingTemplate() { return requestMappingTemplate; }
    public void setRequestMappingTemplate(String template) { this.requestMappingTemplate = template; }

    public String getResponseMappingTemplate() { return responseMappingTemplate; }
    public void setResponseMappingTemplate(String template) { this.responseMappingTemplate = template; }

    public String getFunctionVersion() { return functionVersion; }
    public void setFunctionVersion(String functionVersion) { this.functionVersion = functionVersion; }

    public String getFunctionArn() { return functionArn; }
    public void setFunctionArn(String functionArn) { this.functionArn = functionArn; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    /**
     * The runtime a pipeline function's {@code code} is written for, {@code APPSYNC_JS} in
     * practice. Shares {@link Resolver.ResolverRuntime} with the resolver it runs under: AWS
     * models the two as the same {@code AppSyncRuntime} shape, and a pipeline whose function
     * and resolver disagreed on the runtime could not execute.
     */
    public Resolver.ResolverRuntime getRuntime() { return runtime; }
    public void setRuntime(Resolver.ResolverRuntime runtime) { this.runtime = runtime; }

    public Integer getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(Integer maxBatchSize) { this.maxBatchSize = maxBatchSize; }
}
