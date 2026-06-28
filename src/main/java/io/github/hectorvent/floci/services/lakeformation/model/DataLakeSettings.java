package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.List;

public class DataLakeSettings {
    @JsonProperty("CreateTableDefaultPermissions")
    private List<CreateTableDefaultPermission> createTableDefaultPermissions = new ArrayList<>();

    @JsonProperty("TrustedResourceOwners")
    private List<String> trustedResourceOwners = new ArrayList<>();

    public List<CreateTableDefaultPermission> getCreateTableDefaultPermissions() {
        return createTableDefaultPermissions;
    }

    public void setCreateTableDefaultPermissions(List<CreateTableDefaultPermission> createTableDefaultPermissions) {
        this.createTableDefaultPermissions = createTableDefaultPermissions == null
                ? new ArrayList<>()
                : createTableDefaultPermissions;
    }

    public List<String> getTrustedResourceOwners() {
        return trustedResourceOwners;
    }

    public void setTrustedResourceOwners(List<String> trustedResourceOwners) {
        this.trustedResourceOwners = trustedResourceOwners == null
                ? new ArrayList<>()
                : trustedResourceOwners;
    }
}
