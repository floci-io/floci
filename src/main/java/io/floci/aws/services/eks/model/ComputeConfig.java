package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

/**
 * EKS Auto Mode's compute capability: whether it is on, the built-in node pools it runs and the IAM role their nodes
 * take.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record ComputeConfig(Boolean enabled, List<String> nodePools, String nodeRoleArn) {}
