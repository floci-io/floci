package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DataLakeSettings {

    private Boolean allowExternalDataFiltering;
    private Boolean allowFullTableExternalDataAccess;
    private List<String> authorizedSessionTagValueList;
    private List<PrincipalPermissions> createDatabaseDefaultPermissions;
    private List<PrincipalPermissions> createTableDefaultPermissions;
    private List<DataLakePrincipal> dataLakeAdmins;
    private List<DataLakePrincipal> externalDataFilteringAllowList;
    private Map<String, String> parameters;
    private List<DataLakePrincipal> readOnlyAdmins;
    private List<String> trustedResourceOwners;

    public Boolean getAllowExternalDataFiltering() {
        return allowExternalDataFiltering;
    }

    public void setAllowExternalDataFiltering(Boolean allowExternalDataFiltering) {
        this.allowExternalDataFiltering = allowExternalDataFiltering;
    }

    public Boolean getAllowFullTableExternalDataAccess() {
        return allowFullTableExternalDataAccess;
    }

    public void setAllowFullTableExternalDataAccess(Boolean allowFullTableExternalDataAccess) {
        this.allowFullTableExternalDataAccess = allowFullTableExternalDataAccess;
    }

    public List<String> getAuthorizedSessionTagValueList() {
        return authorizedSessionTagValueList;
    }

    public void setAuthorizedSessionTagValueList(List<String> authorizedSessionTagValueList) {
        this.authorizedSessionTagValueList = authorizedSessionTagValueList;
    }

    public List<PrincipalPermissions> getCreateDatabaseDefaultPermissions() {
        return createDatabaseDefaultPermissions;
    }

    public void setCreateDatabaseDefaultPermissions(List<PrincipalPermissions> createDatabaseDefaultPermissions) {
        this.createDatabaseDefaultPermissions = createDatabaseDefaultPermissions;
    }

    public List<PrincipalPermissions> getCreateTableDefaultPermissions() {
        return createTableDefaultPermissions;
    }

    public void setCreateTableDefaultPermissions(List<PrincipalPermissions> createTableDefaultPermissions) {
        this.createTableDefaultPermissions = createTableDefaultPermissions;
    }

    public List<DataLakePrincipal> getDataLakeAdmins() {
        return dataLakeAdmins;
    }

    public void setDataLakeAdmins(List<DataLakePrincipal> dataLakeAdmins) {
        this.dataLakeAdmins = dataLakeAdmins;
    }

    public List<DataLakePrincipal> getExternalDataFilteringAllowList() {
        return externalDataFilteringAllowList;
    }

    public void setExternalDataFilteringAllowList(List<DataLakePrincipal> externalDataFilteringAllowList) {
        this.externalDataFilteringAllowList = externalDataFilteringAllowList;
    }

    public Map<String, String> getParameters() {
        return parameters;
    }

    public void setParameters(Map<String, String> parameters) {
        this.parameters = parameters;
    }

    public List<DataLakePrincipal> getReadOnlyAdmins() {
        return readOnlyAdmins;
    }

    public void setReadOnlyAdmins(List<DataLakePrincipal> readOnlyAdmins) {
        this.readOnlyAdmins = readOnlyAdmins;
    }

    public List<String> getTrustedResourceOwners() {
        return trustedResourceOwners;
    }

    public void setTrustedResourceOwners(List<String> trustedResourceOwners) {
        this.trustedResourceOwners = trustedResourceOwners;
    }
}
