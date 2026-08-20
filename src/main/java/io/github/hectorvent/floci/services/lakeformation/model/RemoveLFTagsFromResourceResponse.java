package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class RemoveLFTagsFromResourceResponse {
    private List<LFTagError> failures;

    public List<LFTagError> getFailures() {
        return failures;
    }

    public void setFailures(List<LFTagError> failures) {
        this.failures = failures;
    }
}
