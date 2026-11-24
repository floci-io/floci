package io.floci.aws.services.eks.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.runtime.annotations.RegisterForReflection;

import java.util.Set;

/**
 * A cluster's version support policy: with {@code EXTENDED} its Kubernetes version enters extended support at the end
 * of standard support, with {@code STANDARD} the cluster is upgraded then instead.
 */
@RegisterForReflection
@JsonIgnoreProperties(ignoreUnknown = true)
public record UpgradePolicy(String supportType) {

    public static final String STANDARD = "STANDARD";
    public static final String EXTENDED = "EXTENDED";
    public static final Set<String> SUPPORT_TYPES = Set.of(STANDARD, EXTENDED);
    /** The policy EKS gives new and existing clusters. */
    public static final UpgradePolicy DEFAULT = new UpgradePolicy(EXTENDED);
}
