package io.github.hectorvent.floci.services.eks.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
public record AccessEntry(String accessEntryArn, String clusterName, String principalArn, String type,
                          String username, List<String> kubernetesGroups, Map<String, String> tags,
                          double createdAt, double modifiedAt) {}
