package io.floci.aws.services.glue.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A Glue Data Catalog catalog ({@code Catalog}): a federated catalog such as {@code s3tablescatalog}, or one of its children. */
@RegisterForReflection
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GlueCatalog {
    @JsonProperty("CatalogId")
    private String catalogId;
    @JsonProperty("Name")
    private String name;
    @JsonProperty("ResourceArn")
    private String resourceArn;
    @JsonProperty("Description")
    private String description;
    @JsonProperty("Parameters")
    private Map<String, String> parameters;
    @JsonProperty("CreateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant createTime;
    @JsonProperty("UpdateTime")
    @JsonFormat(shape = JsonFormat.Shape.NUMBER)
    private Instant updateTime;
    @JsonProperty("TargetRedshiftCatalog")
    private Map<String, Object> targetRedshiftCatalog;
    @JsonProperty("FederatedCatalog")
    private Map<String, Object> federatedCatalog;
    @JsonProperty("CatalogProperties")
    private Map<String, Object> catalogProperties;
    @JsonProperty("CreateTableDefaultPermissions")
    private List<Map<String, Object>> createTableDefaultPermissions;
    @JsonProperty("CreateDatabaseDefaultPermissions")
    private List<Map<String, Object>> createDatabaseDefaultPermissions;
    @JsonProperty("AllowFullTableExternalDataAccess")
    private String allowFullTableExternalDataAccess;

    public GlueCatalog() {}

    public String getCatalogId() { return catalogId; }
    public void setCatalogId(String catalogId) { this.catalogId = catalogId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getResourceArn() { return resourceArn; }
    public void setResourceArn(String resourceArn) { this.resourceArn = resourceArn; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Map<String, String> getParameters() { return parameters; }
    public void setParameters(Map<String, String> parameters) { this.parameters = parameters; }
    public Instant getCreateTime() { return createTime; }
    public void setCreateTime(Instant createTime) { this.createTime = createTime; }
    public Instant getUpdateTime() { return updateTime; }
    public void setUpdateTime(Instant updateTime) { this.updateTime = updateTime; }
    public Map<String, Object> getTargetRedshiftCatalog() { return targetRedshiftCatalog; }
    public void setTargetRedshiftCatalog(Map<String, Object> targetRedshiftCatalog) {
        this.targetRedshiftCatalog = targetRedshiftCatalog;
    }
    public Map<String, Object> getFederatedCatalog() { return federatedCatalog; }
    public void setFederatedCatalog(Map<String, Object> federatedCatalog) { this.federatedCatalog = federatedCatalog; }
    public Map<String, Object> getCatalogProperties() { return catalogProperties; }
    public void setCatalogProperties(Map<String, Object> catalogProperties) { this.catalogProperties = catalogProperties; }
    public List<Map<String, Object>> getCreateTableDefaultPermissions() { return createTableDefaultPermissions; }
    public void setCreateTableDefaultPermissions(List<Map<String, Object>> permissions) {
        this.createTableDefaultPermissions = permissions;
    }
    public List<Map<String, Object>> getCreateDatabaseDefaultPermissions() { return createDatabaseDefaultPermissions; }
    public void setCreateDatabaseDefaultPermissions(List<Map<String, Object>> permissions) {
        this.createDatabaseDefaultPermissions = permissions;
    }
    public String getAllowFullTableExternalDataAccess() { return allowFullTableExternalDataAccess; }
    public void setAllowFullTableExternalDataAccess(String value) { this.allowFullTableExternalDataAccess = value; }
}
