package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Objects;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public class EncryptionConfig {

    @JsonProperty("resources")
    private List<String> resources;

    @JsonProperty("provider")
    private Provider provider;

    public EncryptionConfig() {}

    public EncryptionConfig(List<String> resources, Provider provider) {
        this.resources = resources;
        this.provider = provider;
    }

    public List<String> getResources() {
        return resources;
    }

    public void setResources(List<String> resources) {
        this.resources = resources;
    }

    public Provider getProvider() {
        return provider;
    }

    public void setProvider(Provider provider) {
        this.provider = provider;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        EncryptionConfig that = (EncryptionConfig) o;
        return Objects.equals(resources, that.resources) && Objects.equals(provider, that.provider);
    }

    @Override
    public int hashCode() {
        return Objects.hash(resources, provider);
    }
}
