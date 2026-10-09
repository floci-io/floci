package io.github.hectorvent.floci.services.efs.model;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@JsonNaming(PropertyNamingStrategies.UpperCamelCaseStrategy.class)
@RegisterForReflection
public class PutLifecycleConfigurationResponse {
    private List<LifecyclePolicy> lifecyclePolicies;
    public List<LifecyclePolicy> getLifecyclePolicies() { return lifecyclePolicies; }
    public void setLifecyclePolicies(List<LifecyclePolicy> lifecyclePolicies) { this.lifecyclePolicies = lifecyclePolicies; }
}
