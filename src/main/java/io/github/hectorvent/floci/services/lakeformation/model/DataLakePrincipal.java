package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
public class DataLakePrincipal {

    private String dataLakePrincipalIdentifier;

    public String getDataLakePrincipalIdentifier() {
        return dataLakePrincipalIdentifier;
    }

    public void setDataLakePrincipalIdentifier(String dataLakePrincipalIdentifier) {
        this.dataLakePrincipalIdentifier = dataLakePrincipalIdentifier;
    }
}
