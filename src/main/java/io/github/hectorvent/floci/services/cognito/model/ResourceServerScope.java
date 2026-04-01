package io.github.hectorvent.floci.services.cognito.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResourceServerScope {
    private String scopeName;
    private String scopeDescription;

    public String getScopeName() { return scopeName; }
    public void setScopeName(String scopeName) { this.scopeName = scopeName; }

    public String getScopeDescription() { return scopeDescription; }
    public void setScopeDescription(String scopeDescription) { this.scopeDescription = scopeDescription; }
}
