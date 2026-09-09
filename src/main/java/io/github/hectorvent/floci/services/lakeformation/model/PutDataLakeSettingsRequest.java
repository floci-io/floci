package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PutDataLakeSettingsRequest {

    private String catalogId;
    private DataLakeSettings dataLakeSettings;

    public String getCatalogId() {
        return catalogId;
    }

    public void setCatalogId(String catalogId) {
        this.catalogId = catalogId;
    }

    public DataLakeSettings getDataLakeSettings() {
        return dataLakeSettings;
    }

    public void setDataLakeSettings(DataLakeSettings dataLakeSettings) {
        this.dataLakeSettings = dataLakeSettings;
    }
}
