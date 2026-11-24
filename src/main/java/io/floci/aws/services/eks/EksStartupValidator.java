package io.floci.aws.services.eks;

import io.floci.aws.config.EmulatorConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;

/**
 * Refuses to start Floci with an invalid {@code floci.services.eks.endpoint-host}, or, with the VPC pod network on, a
 * {@code floci.services.eks.vpc-pod-prefixes} out of range. Checked once here, before persisted clusters are restored,
 * so a typo stops the boot instead of failing every cluster on restore. Metadata-only EKS (disabled or mock) never uses
 * the settings, so they are not checked there.
 */
@ApplicationScoped
public class EksStartupValidator {

    /** A node needs a prefix for its pods, and sixteen /28 prefixes already fill a /24 subnet. */
    static final int MAX_VPC_POD_PREFIXES = 16;

    private final EmulatorConfig config;

    @Inject
    public EksStartupValidator(EmulatorConfig config) {
        this.config = config;
    }

    void onStart(@Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent ignored) {
        if (config.services().eks().enabled() && !config.services().eks().mock()) {
            EksClusterManager.endpointHostFrom(config.services().eks().endpointHost());
            requireVpcPodPrefixes(config.services().eks());
        }
    }

    static void requireVpcPodPrefixes(EmulatorConfig.EksServiceConfig eks) {
        int prefixes = eks.vpcPodPrefixes();
        if (eks.vpcPodNetwork() && (prefixes < 1 || prefixes > MAX_VPC_POD_PREFIXES)) {
            throw new IllegalArgumentException("floci.services.eks.vpc-pod-prefixes must be between 1 and "
                    + MAX_VPC_POD_PREFIXES + " with floci.services.eks.vpc-pod-network on, not " + prefixes);
        }
    }
}
