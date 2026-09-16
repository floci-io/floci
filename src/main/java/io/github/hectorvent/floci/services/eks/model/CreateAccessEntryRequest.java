package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;
import java.util.Map;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateAccessEntryRequest(String principalArn, String type, String username,
                                      List<String> kubernetesGroups, Map<String, String> tags,
                                      String clientRequestToken) {}
