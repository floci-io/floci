package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * Caller-supplied containerd host configuration for one registry namespace, written into the
 * cluster's k3s container as {@code certs.d/<host>/hosts.toml} rather than into the generated
 * {@code registries.yaml}, which has no way to attach request headers to a mirror. {@code host}
 * excludes that hostname from the generated ECR mirror list when it collides with one: see
 * {@code EksClusterManager#buildRegistriesYaml}.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegistryHostConfig(String host, List<RegistryEndpoint> endpoints,
                                 List<String> capabilities, Boolean skipVerify) {}
