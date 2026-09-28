package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Objects;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class LogSetup {

    @JsonProperty("types")
    private List<String> types;

    @JsonProperty("enabled")
    private Boolean enabled;

    public LogSetup() {}

    public LogSetup(List<String> types, Boolean enabled) {
        this.types = types;
        this.enabled = enabled;
    }

    public List<String> getTypes() {
        return types;
    }

    public void setTypes(List<String> types) {
        this.types = types;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        LogSetup logSetup = (LogSetup) o;
        return Objects.equals(types, logSetup.types) && Objects.equals(enabled, logSetup.enabled);
    }

    @Override
    public int hashCode() {
        return Objects.hash(types, enabled);
    }
}
