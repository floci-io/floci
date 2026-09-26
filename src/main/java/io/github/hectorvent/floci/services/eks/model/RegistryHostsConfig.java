package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/** Request/response body for {@code _floci/eks/clusters/{name}/registry-hosts}. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryHostsConfig(List<RegistryHostConfig> hosts) {}
