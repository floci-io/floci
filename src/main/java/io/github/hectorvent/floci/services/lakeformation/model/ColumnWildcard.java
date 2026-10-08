package io.github.hectorvent.floci.services.lakeformation.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
@RegisterForReflection
@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ColumnWildcard {
    private List<String> excludedColumnNames;

    public List<String> getExcludedColumnNames() {
        return excludedColumnNames;
    }

    public void setExcludedColumnNames(List<String> excludedColumnNames) {
        this.excludedColumnNames = excludedColumnNames;
    }
}
