package io.github.hectorvent.floci.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.List;

@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AddonVersionInfo(
        String addonVersion,
        List<String> architecture,
        List<Compatibility> compatibilities,
        Boolean requiresConfiguration,
        Boolean requiresIamPermissions
) {}
