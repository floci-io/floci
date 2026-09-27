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
     * The VPC id of the resource reachable at {@code clientAddress}, or empty when this source
     * launched nothing there. Empty is not an error: a query no one claims has no VPC, so no rules
     * apply to it.
     */
    Optional<String> vpcIdForClient(String clientAddress);
}
