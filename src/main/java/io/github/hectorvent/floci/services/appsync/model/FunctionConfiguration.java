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
    private String arn;
    private String code;

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

    public String getArn() { return arn; }
    public void setArn(String arn) { this.arn = arn; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
}
