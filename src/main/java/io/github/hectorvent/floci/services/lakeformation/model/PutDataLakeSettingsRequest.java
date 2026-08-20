package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
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
