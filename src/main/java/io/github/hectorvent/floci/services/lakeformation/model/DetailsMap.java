package io.github.hectorvent.floci.services.lakeformation.model;

import io.quarkus.runtime.annotations.RegisterForReflection;
import java.util.List;

@RegisterForReflection
public class DetailsMap {
    private List<String> resourceShare;

    public List<String> getResourceShare() {
        return resourceShare;
    }

    public void setResourceShare(List<String> resourceShare) {
        this.resourceShare = resourceShare;
    }
}
