package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GrantPermissionsRequest {
    private String catalogId;
    private Condition condition;
    private List<String> permissions;
    private List<String> permissionsWithGrantOption;
    private DataLakePrincipal principal;
    private Resource resource;

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId;
    }

    public Condition getCondition() {
        return condition;
    }

    public void setCondition(Condition condition) {
        this.condition = condition;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    public List<String> getPermissionsWithGrantOption() {
        return permissionsWithGrantOption;
    }

    public void setPermissionsWithGrantOption(List<String> permissionsWithGrantOption) {
        this.permissionsWithGrantOption = permissionsWithGrantOption;
    }

    public DataLakePrincipal getPrincipal() {
        return principal;
    }

    public void setPrincipal(DataLakePrincipal principal) {
        this.principal = principal;
    }

    public Resource getResource() {
        return resource;
    }

    public void setResource(Resource resource) {
        this.resource = resource;
    }
}
