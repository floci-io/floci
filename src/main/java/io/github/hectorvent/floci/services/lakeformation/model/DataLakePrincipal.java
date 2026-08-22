package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
public class DataLakePrincipal {

    private String dataLakePrincipalIdentifier;

    public String getDataLakePrincipalIdentifier() {
        return dataLakePrincipalIdentifier;
    }

    public void setDataLakePrincipalIdentifier(String dataLakePrincipalIdentifier) {
        this.dataLakePrincipalIdentifier = dataLakePrincipalIdentifier;
    }
}
