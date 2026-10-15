package io.github.hectorvent.floci.services.autoscaling.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

/** Source identity recorded before a refresh launch can be accepted by EC2. */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstanceRefreshLaunch(String launchConfigurationName, String launchTemplateId,
                                    String launchTemplateName, String launchTemplateVersion,
                                    String instanceType, String availabilityZone) {
}
