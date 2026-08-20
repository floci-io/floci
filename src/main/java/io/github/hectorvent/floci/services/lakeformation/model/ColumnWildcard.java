package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class ColumnWildcard {
    private List<String> excludedColumnNames;

    public List<String> getExcludedColumnNames() {
        return excludedColumnNames;
    }

    public void setExcludedColumnNames(List<String> excludedColumnNames) {
        this.excludedColumnNames = excludedColumnNames;
    }
}
