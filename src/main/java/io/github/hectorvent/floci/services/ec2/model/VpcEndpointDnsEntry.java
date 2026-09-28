package io.github.hectorvent.floci.services.ec2.model;

/**
 * One {@code DnsEntry} of an interface VPC endpoint: a name the endpoint answers to, and the
 * zone holding that name.
 *
 * @see <a href="https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DnsEntry.html">AWS EC2 DnsEntry</a>
 */
public record VpcEndpointDnsEntry(
    String dnsName,
    String hostedZoneId
) {}
