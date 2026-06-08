package io.github.hectorvent.floci.services.appsync.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Resolver {
    private String apiId;
    private String typeName;
    private String fieldName;
    private String dataSourceName;
    private String functionId;
    private String requestMappingTemplate;
    private String responseMappingTemplate;
    private ResolverKind kind;
    private String code;
    private ResolverRuntime runtime;
    private String resolverArn;
    private Map<String, Object> cachingConfig;
    private Integer maxBatchSize;
    private String metricsConfig;
    private Map<String, Object> pipelineConfig;
    private Map<String, Object> syncConfig;

    public String getApiId() { return apiId; }
    public void setApiId(String apiId) { this.apiId = apiId; }

    public String getTypeName() { return typeName; }
    public void setTypeName(String typeName) { this.typeName = typeName; }

    public String getFieldName() { return fieldName; }
    public void setFieldName(String fieldName) { this.fieldName = fieldName; }

    public String getDataSourceName() { return dataSourceName; }
    public void setDataSourceName(String dataSourceName) { this.dataSourceName = dataSourceName; }

    public String getFunctionId() { return functionId; }
    public void setFunctionId(String functionId) { this.functionId = functionId; }

    public String getRequestMappingTemplate() { return requestMappingTemplate; }
    public void setRequestMappingTemplate(String template) { this.requestMappingTemplate = template; }

    public String getResponseMappingTemplate() { return responseMappingTemplate; }
    public void setResponseMappingTemplate(String template) { this.responseMappingTemplate = template; }

    public ResolverKind getKind() { return kind; }
    public void setKind(ResolverKind kind) { this.kind = kind; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public ResolverRuntime getRuntime() { return runtime; }
    public void setRuntime(ResolverRuntime runtime) { this.runtime = runtime; }

    public String getResolverArn() { return resolverArn; }
    public void setResolverArn(String resolverArn) { this.resolverArn = resolverArn; }

    public Map<String, Object> getCachingConfig() { return cachingConfig; }
    public void setCachingConfig(Map<String, Object> cachingConfig) { this.cachingConfig = cachingConfig; }

    public Integer getMaxBatchSize() { return maxBatchSize; }
    public void setMaxBatchSize(Integer maxBatchSize) { this.maxBatchSize = maxBatchSize; }

    public String getMetricsConfig() { return metricsConfig; }
    public void setMetricsConfig(String metricsConfig) { this.metricsConfig = metricsConfig; }

    public Map<String, Object> getPipelineConfig() { return pipelineConfig; }
    public void setPipelineConfig(Map<String, Object> pipelineConfig) { this.pipelineConfig = pipelineConfig; }

    public Map<String, Object> getSyncConfig() { return syncConfig; }
    public void setSyncConfig(Map<String, Object> syncConfig) { this.syncConfig = syncConfig; }

    @RegisterForReflection
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResolverRuntime {
        private ResolverRuntimeName name;
        private String runtimeVersion;

        public ResolverRuntimeName getName() { return name; }
        public void setName(ResolverRuntimeName name) { this.name = name; }

        public String getRuntimeVersion() { return runtimeVersion; }
        public void setRuntimeVersion(String runtimeVersion) { this.runtimeVersion = runtimeVersion; }
    }
}
