package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class GetDataLakeSettingsResponse {

    private DataLakeSettings dataLakeSettings;

    public DataLakeSettings getDataLakeSettings() {
        return dataLakeSettings;
    }

    public void setDataLakeSettings(DataLakeSettings dataLakeSettings) {
        this.dataLakeSettings = dataLakeSettings;
    }
}
