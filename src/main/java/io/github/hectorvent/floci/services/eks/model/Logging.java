package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Objects;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class Logging {

    @JsonProperty("clusterLogging")
    private List<LogSetup> clusterLogging;

    public Logging() {}

    public Logging(List<LogSetup> clusterLogging) {
        this.clusterLogging = clusterLogging;
    }

    public List<LogSetup> getClusterLogging() {
        return clusterLogging;
    }

    public void setClusterLogging(List<LogSetup> clusterLogging) {
        this.clusterLogging = clusterLogging;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        Logging logging = (Logging) o;
        return Objects.equals(clusterLogging, logging.clusterLogging);
    }

    @Override
    public int hashCode() {
        return Objects.hash(clusterLogging);
    }
}
