package io.floci.aws.testing;

import io.floci.aws.services.ec2.model.IpPermission;
import io.floci.aws.services.ec2.model.IpRange;
import io.floci.aws.services.ec2.model.Ipv6Range;

/** Security-group permissions for tests. */
public final class IpPermissions {

    private IpPermissions() {
    }

    /** A TCP permission on one port, open to each CIDR given: an IPv6 one goes to {@code Ipv6Ranges}. */
    public static IpPermission tcp(int port, String... cidrs) {
        return tcp(port, port, cidrs);
    }

    /** A TCP permission on a port range, open to each CIDR given: an IPv6 one goes to {@code Ipv6Ranges}. */
    public static IpPermission tcp(int fromPort, int toPort, String... cidrs) {
        IpPermission permission = new IpPermission();
        permission.setIpProtocol("tcp");
        permission.setFromPort(fromPort);
        permission.setToPort(toPort);
        for (String cidr : cidrs) {
            if (cidr.indexOf(':') >= 0) {
                permission.getIpv6Ranges().add(new Ipv6Range(cidr));
            } else {
                permission.getIpRanges().add(new IpRange(cidr));
            }
        }
        return permission;
    }
}
