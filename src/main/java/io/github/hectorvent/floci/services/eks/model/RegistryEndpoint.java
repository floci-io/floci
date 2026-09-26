package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Map;

/**
 * One candidate endpoint for a {@link RegistryHostConfig}, rendered as a {@code [host."<url>"]}
 * table in containerd's hosts.toml. {@code headers} become the nested {@code .header} table,
 * which is the one thing k3s's {@code registries.yaml} cannot express.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryEndpoint(String url, Map<String, String> headers) {}
