package io.github.hectorvent.floci.services.ec2.model;

import java.util.List;

/**
 * Result of paginated NAT gateway listing.
 *
 * @param natGateways List of NAT gateways for this page
 * @param nextToken   Token for next page, or {@code null} if no more pages
 */
public record NatGatewayListResult(
    List<NatGateway> natGateways,
    String nextToken
) {}
