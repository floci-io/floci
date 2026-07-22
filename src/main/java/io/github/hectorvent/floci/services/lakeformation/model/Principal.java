package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Principal {
    @JsonProperty("DataLakePrincipalIdentifier")
    private String dataLakePrincipalIdentifier;

    public String getDataLakePrincipalIdentifier() {
        return dataLakePrincipalIdentifier;
    }

    public void setDataLakePrincipalIdentifier(String dataLakePrincipalIdentifier) {
        this.dataLakePrincipalIdentifier = dataLakePrincipalIdentifier;
    }
}
