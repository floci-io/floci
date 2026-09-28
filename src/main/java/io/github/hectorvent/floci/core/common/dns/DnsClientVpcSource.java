package io.github.hectorvent.floci.core.common.dns;

import java.util.Optional;

/**
 * Tells {@link EmbeddedDnsServer} which VPC a query came from, so rules associated with that VPC
 * can be applied and rules that are not can be ignored.
 *
 * <p>The DNS wire protocol carries no VPC identifier, but the query's source address identifies the
 * container that sent it, and a service that launches containers for an AWS resource knows the VPC
 * that resource sits in. Implemented by those services and discovered through CDI.
 */
public interface DnsClientVpcSource {

    /**
     * Where a query came from. The account is part of it because a VPC id alone is not an identity:
     * any account can associate a resolver rule with any VPC id it cares to name, so a rule may only
     * steer queries from a resource its own account owns. The region is part of it because Route 53
     * Resolver is regional, so a rule only governs queries from the region it was created in.
     */
    record ClientVpc(String accountId, String region, String vpcId) {
    }

    /**
     * The account, region and VPC of the resource reachable at {@code clientAddress}, or empty when
     * this source launched nothing there. Empty is not an error: a query no one claims has no VPC, so
     * no rules apply to it.
     */
    Optional<ClientVpc> vpcForClient(String clientAddress);
}
