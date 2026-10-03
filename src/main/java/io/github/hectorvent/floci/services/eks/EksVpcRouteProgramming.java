package io.github.hectorvent.floci.services.eks;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.NetworkInterface;
import io.github.hectorvent.floci.services.ec2.model.Route;
import io.github.hectorvent.floci.services.ec2.model.RouteTable;
import io.github.hectorvent.floci.services.ec2.model.RouteTableAssociation;
import io.github.hectorvent.floci.services.ec2.net.Cidr4;
import io.github.hectorvent.floci.services.eks.model.Cluster;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class EksVpcRouteProgramming {

    private static final Logger LOG = Logger.getLogger(EksVpcRouteProgramming.class);

    private EksVpcRouteProgramming() {}

    public record VpcRouteEntry(String destinationCidrBlock, String gatewayIp) {}

    public static boolean isValidIpv4Cidr(String cidr) {
        return cidr != null && !cidr.contains(" ") && Cidr4.parse(cidr.trim()).isPresent();
    }

    public static boolean isValidIpv4Address(String ip) {
        return ip != null && !ip.contains(" ") && Cidr4.parse(ip.trim() + "/32").isPresent();
    }

    public static Optional<String[]> buildRoutingCommand(List<VpcRouteEntry> routes) {
        return buildRoutingCommand(routes, Collections.emptyList());
    }

    public static Optional<String[]> buildRoutingCommand(List<VpcRouteEntry> routesToApply,
                                                         Collection<String> routesToDelete) {
        boolean hasApply = routesToApply != null && !routesToApply.isEmpty();
        boolean hasDelete = routesToDelete != null && !routesToDelete.isEmpty();
        if (!hasApply && !hasDelete) {
            return Optional.empty();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("set -eu\n");
        sb.append("IP=$(command -v ip 2>/dev/null || command -v /sbin/ip 2>/dev/null || command -v /bin/ip 2>/dev/null || true)\n");
        sb.append("if [ -z \"$IP\" ]; then\n");
        sb.append("  echo 'ip command not found in container' >&2\n");
        sb.append("  exit 1\n");
        sb.append("fi\n");

        boolean actionAdded = false;
        if (hasDelete) {
            for (String dest : routesToDelete) {
                if (isValidIpv4Cidr(dest)) {
                    sb.append("if [ -n \"$($IP route show \"").append(dest).append("\" 2>/dev/null)\" ]; then\n");
                    sb.append("  $IP route del \"").append(dest).append("\"\n");
                    sb.append("fi\n");
                    actionAdded = true;
                }
            }
        }

        if (hasApply) {
            for (VpcRouteEntry entry : routesToApply) {
                if (entry != null && isValidIpv4Cidr(entry.destinationCidrBlock())
                        && isValidIpv4Address(entry.gatewayIp())) {
                    sb.append("$IP route replace \"").append(entry.destinationCidrBlock())
                      .append("\" via \"").append(entry.gatewayIp()).append("\"\n");
                    actionAdded = true;
                }
            }
        }

        if (!actionAdded) {
            return Optional.empty();
        }

        sb.append("mkdir -p /run\n");
        sb.append("cat << 'EOF' > /run/floci-vpc-routes.txt\n");
        if (hasApply) {
            for (VpcRouteEntry entry : routesToApply) {
                if (entry != null && isValidIpv4Cidr(entry.destinationCidrBlock())
                        && isValidIpv4Address(entry.gatewayIp())) {
                    sb.append(entry.destinationCidrBlock()).append("\n");
                }
            }
        }
        sb.append("EOF\n");

        return Optional.of(new String[]{"sh", "-c", sb.toString().trim()});
    }

    public static List<RouteTable> findApplicableRouteTables(String vpcId, List<String> subnetIds, List<RouteTable> vpcRouteTables) {
        if (vpcId == null || vpcId.isBlank() || vpcRouteTables == null || vpcRouteTables.isEmpty()) {
            return List.of();
        }
        RouteTable mainTable = vpcRouteTables.stream()
                .filter(rt -> rt.getAssociations() != null && rt.getAssociations().stream().anyMatch(RouteTableAssociation::isMain))
                .findFirst()
                .orElse(null);

        if (subnetIds == null || subnetIds.isEmpty()) {
            return mainTable != null ? List.of(mainTable) : List.of();
        }

        Set<RouteTable> result = new LinkedHashSet<>();
        for (String subnetId : subnetIds) {
            RouteTable explicit = vpcRouteTables.stream()
                    .filter(rt -> rt.getAssociations() != null && rt.getAssociations().stream()
                            .anyMatch(a -> !a.isMain() && subnetId.equals(a.getSubnetId())))
                    .findFirst()
                    .orElse(null);
            if (explicit != null) {
                result.add(explicit);
            } else if (mainTable != null) {
                result.add(mainTable);
            }
        }
        return new ArrayList<>(result);
    }

    /** {@code region} is the cluster's own, as {@code EksClusterManager.clusterRegion} resolves it. */
    public static List<VpcRouteEntry> resolveProgrammableRoutes(Cluster cluster, String region,
            List<RouteTable> applicableTables, Ec2Service ec2Service) {
        if (cluster == null || applicableTables == null || applicableTables.isEmpty() || ec2Service == null) {
            return List.of();
        }
        String accountId = clusterAccountId(cluster);
        Map<String, String> resolved = new LinkedHashMap<>();

        for (RouteTable table : applicableTables) {
            if (table.getRoutes() == null) {
                continue;
            }
            for (Route route : table.getRoutes()) {
                String dest = route.getDestinationCidrBlock();
                if (dest == null || dest.isBlank() || "0.0.0.0/0".equals(dest) || "default".equalsIgnoreCase(dest)) {
                    continue;
                }
                if (!isValidIpv4Cidr(dest)) {
                    LOG.warnv("Cannot program route for EKS cluster {0}: invalid IPv4 CIDR destination {1}",
                            cluster.getName(), dest);
                    continue;
                }
                String gatewayIp = null;
                if (isSet(route.getInstanceId())) {
                    Instance targetInst = accountId != null
                            ? ec2Service.findInstanceById(accountId, route.getInstanceId())
                            : ec2Service.findInstanceById(route.getInstanceId());
                    if (targetInst == null) {
                        LOG.warnv("Cannot program route {0} for EKS cluster {1}: target instance {2} not found",
                                dest, cluster.getName(), route.getInstanceId());
                        continue;
                    }
                    gatewayIp = resolveInstanceGatewayIp(targetInst);
                    if (!isSet(gatewayIp)) {
                        LOG.warnv("Cannot program route {0} for EKS cluster {1}: target instance {2} has no IP address",
                                dest, cluster.getName(), route.getInstanceId());
                        continue;
                    }
                } else if (isSet(route.getNetworkInterfaceId())) {
                    try {
                        NetworkInterface ni = ec2Service.describeNetworkInterfaces(accountId, region,
                                        List.of(route.getNetworkInterfaceId()), Map.<String, List<String>>of())
                                .networkInterfaces().stream().findFirst().orElse(null);
                        if (ni == null) {
                            LOG.warnv("Cannot program route {0} for EKS cluster {1}: target ENI {2} not found",
                                    dest, cluster.getName(), route.getNetworkInterfaceId());
                            continue;
                        }
                        gatewayIp = resolveEniGatewayIp(ni, ec2Service, accountId);
                        if (!isSet(gatewayIp)) {
                            LOG.warnv("Cannot program route {0} for EKS cluster {1}: target ENI {2} has no reachable IP address",
                                    dest, cluster.getName(), route.getNetworkInterfaceId());
                            continue;
                        }
                    } catch (AwsException e) {
                        LOG.warnv("Cannot program route {0} for EKS cluster {1}: target ENI {2} not found: {3}",
                                dest, cluster.getName(), route.getNetworkInterfaceId(), e.getMessage());
                        continue;
                    }
                }
                if (gatewayIp != null && isValidIpv4Address(gatewayIp)) {
                    String canonicalDest = Cidr4.parse(dest).map(Cidr4::toString).orElse(dest.trim());
                    resolved.put(canonicalDest, gatewayIp);
                } else if (gatewayIp != null) {
                    LOG.warnv("Cannot program route {0} for EKS cluster {1}: invalid gateway IP {2}",
                            dest, cluster.getName(), gatewayIp);
                }
            }
        }

        List<VpcRouteEntry> entries = new ArrayList<>();
        resolved.forEach((dest, gw) -> entries.add(new VpcRouteEntry(dest, gw)));
        return entries;
    }

    static String resolveInstanceGatewayIp(Instance instance) {
        if (instance == null) {
            return null;
        }
        if (instance.getState() != null) {
            String state = instance.getState().getName();
            if ("terminated".equalsIgnoreCase(state) || "shutting-down".equalsIgnoreCase(state)) {
                return null;
            }
        }
        if (isSet(instance.getContainerBridgeIp())) {
            return instance.getContainerBridgeIp();
        }
        if (isSet(instance.getPrivateIpAddress())) {
            return instance.getPrivateIpAddress();
        }
        return instance.getImdsSourceIp();
    }

    static String resolveEniGatewayIp(NetworkInterface ni, Ec2Service ec2Service, String accountId) {
        if (ni == null) {
            return null;
        }
        // An unattached ENI has no reachable host container
        if (ni.getAttachment() == null
                || !isSet(ni.getAttachment().getInstanceId())
                || (ni.getAttachment().getStatus() != null && !"attached".equalsIgnoreCase(ni.getAttachment().getStatus()))) {
            return null;
        }
        String instanceId = ni.getAttachment().getInstanceId();
        Instance niInst = accountId != null
                ? ec2Service.findInstanceById(accountId, instanceId)
                : ec2Service.findInstanceById(instanceId);
        if (niInst == null) {
            return null;
        }
        if (niInst.getState() != null) {
            String state = niInst.getState().getName();
            if ("terminated".equalsIgnoreCase(state) || "shutting-down".equalsIgnoreCase(state)) {
                return null;
            }
        }

        // When security-group enforcement is enabled, the target instance runs in a firewall
        // helper's network namespace and is not attached to the VPC network at the ENI's private IP.
        // In that case, or when VPC networking is disabled, the ENI's private IP is unreachable
        // on the VPC network and the instance's reachable gateway IP must be used.
        boolean vpcNetworkActive = ec2Service != null
                && ec2Service.isVpcNetworkEnabled()
                && !ec2Service.isSecurityGroupEnforcementEnabled();

        if (vpcNetworkActive && isSet(ni.getPrivateIpAddress())) {
            return ni.getPrivateIpAddress();
        }

        return resolveInstanceGatewayIp(niInst);
    }

    private static String clusterAccountId(Cluster cluster) {
        if (cluster == null) {
            return null;
        }
        if (isSet(cluster.getAccountId())) {
            return cluster.getAccountId();
        }
        if (isSet(cluster.getArn())) {
            String[] parts = cluster.getArn().split(":");
            if (parts.length > 4 && isSet(parts[4])) {
                return parts[4];
            }
        }
        return null;
    }


    private static boolean isSet(String s) {
        return s != null && !s.isBlank();
    }
}
