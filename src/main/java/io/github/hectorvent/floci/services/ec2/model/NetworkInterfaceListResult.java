package io.github.hectorvent.floci.services.ec2.model;

import java.util.List;

/**
 * Result of paginated network interface listing.
 *
 * <p>Used by {@code DescribeNetworkInterfaces} to return a page of network interfaces
 * along with a cursor token for fetching the next page.</p>
 *
 * @param networkInterfaces List of network interfaces for this page
 * @param nextToken         Token for next page, or {@code null} if no more pages
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeNetworkInterfaces.html">AWS EC2 DescribeNetworkInterfaces</a>
 */
public record NetworkInterfaceListResult(
    List<NetworkInterface> networkInterfaces,
    String nextToken
) {}
