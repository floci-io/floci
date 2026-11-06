package io.floci.aws.services.ec2;

import io.floci.aws.services.ec2.model.Instance;

/**
 * Whether an instance has an automatic public IPv4 address, decided the same way with and without
 * containers; only the address itself depends on the mode: the container's reachable address, or
 * where there is no container the one {@code mock-public-addresses} chooses, by default none.
 *
 * <p>As on AWS, an instance takes one when its launch asked for it (the subnet's
 * MapPublicIpOnLaunch, or AssociatePublicIpAddress, which wins either way) on an interface the
 * launch created; the launch records that answer in {@link Instance#isAssociatePublicIp()}. A stop
 * or a disassociated Elastic IP releases the address, and its replacement is held back while a
 * second interface is attached. An Elastic IP on the primary private address takes precedence.
 */
final class AutomaticPublicAddress {

    private AutomaticPublicAddress() {
    }

    /**
     * @param replacement true when the address would replace one a stop or a disassociated Elastic
     *        IP released
     */
    static boolean isDue(Instance instance, boolean replacement) {
        if (!instance.isAssociatePublicIp()) {
            return false;
        }
        return !replacement || instance.getNetworkInterfaces() == null || instance.getNetworkInterfaces().size() <= 1;
    }
}
