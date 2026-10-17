package io.github.hectorvent.floci.services.ec2.model;

import java.util.List;

/**
 * One page of {@code DescribeEgressOnlyInternetGateways}, with the cursor for the next page or
 * {@code null} when there is none.
 */
public record EgressOnlyInternetGatewayListResult(
    List<EgressOnlyInternetGateway> egressOnlyInternetGateways,
    String nextToken
) {}
