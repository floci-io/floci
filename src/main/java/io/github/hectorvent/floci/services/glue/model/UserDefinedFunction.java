package io.github.hectorvent.floci.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;

@RegisterForReflection
public class UserDefinedFunction {
    @JsonProperty("FunctionName")
    private String functionName;
    @JsonProperty("DatabaseName")
    private String databaseName;
    @JsonProperty("ClassName")
    private String className;
    @JsonProperty("FunctionType")
    private String functionType;
    @JsonProperty("OwnerName")
    private String ownerName;
    @JsonProperty("OwnerType")
    private String ownerType;
    @JsonProperty("CreateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createTime;
    @JsonProperty("ResourceUris")
    private List<ResourceUri> resourceUris;

    public UserDefinedFunction() {}

    public String getFunctionName() { return functionName; }
    public void setFunctionName(String functionName) { this.functionName = functionName; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getClassName() { return className; }
    public void setClassName(String className) { this.className = className; }
    public String getFunctionType() { return functionType; }
    public void setFunctionType(String functionType) { this.functionType = functionType; }
    public String getOwnerName() { return ownerName; }
    public void setOwnerName(String ownerName) { this.ownerName = ownerName; }
    public String getOwnerType() { return ownerType; }
    public void setOwnerType(String ownerType) { this.ownerType = ownerType; }
    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }
    public List<ResourceUri> getResourceUris() { return resourceUris; }
    public void setResourceUris(List<ResourceUri> resourceUris) { this.resourceUris = resourceUris; }
}
