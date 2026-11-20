package io.floci.aws.services.elbv2;

import io.floci.aws.core.common.AwsArnUtils;
import io.floci.aws.core.common.SsrfProtection;
import io.floci.aws.services.ec2.Ec2InstancePorts;
import io.floci.aws.services.ec2.Ec2Service;
import io.floci.aws.services.ec2.model.Instance;
import io.floci.aws.services.elbv2.model.TargetGroup;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Optional;

final class ElbV2TargetResolver {

    private ElbV2TargetResolver() {}

    /**
     * Where a target is reached: an {@code ip} target at its own address, an {@code instance} target at
     * whatever serves that instance port. A port an ECS task on the instance publishes is reached at the
     * task's container; any other port at the instance's own container, as AWS forwards to the instance's
     * primary address. An instance Floci cannot place keeps its id, which the caller then fails to reach.
     */
    static Ec2InstancePorts.Endpoint resolve(Ec2Service ec2Service, Ec2InstancePorts instancePorts,
                                             TargetGroup targetGroup, String targetId, int port) {
        if (targetId == null || targetId.isBlank() || targetGroup == null
                || !"instance".equals(targetGroup.getTargetType())) {
            return new Ec2InstancePorts.Endpoint(targetId, port);
        }
        String accountId = extractAccountId(targetGroup.getTargetGroupArn());
        if (instancePorts != null) {
            Optional<Ec2InstancePorts.Endpoint> published = instancePorts.find(accountId, targetId, port);
            if (published.isPresent()) {
                return published.get();
            }
        }
        return new Ec2InstancePorts.Endpoint(resolveHost(ec2Service, targetId, accountId), port);
    }

    private static String resolveHost(Ec2Service ec2Service, String targetId, String accountId) {
        if (ec2Service == null) {
            return targetId;
        }
        Instance instance = accountId != null
                ? ec2Service.findInstanceById(accountId, targetId)
                : ec2Service.findInstanceById(targetId);
        if (instance == null) {
            return targetId;
        }
        String containerBridgeIp = instance.getContainerBridgeIp();
        if (containerBridgeIp != null && !containerBridgeIp.isBlank()) {
            return containerBridgeIp;
        }
        return targetId;
    }

    /** Resolves once, rejects metadata addresses, and returns the address to connect to instead of the name. */
    static String resolveCheckedAddress(String host) throws IOException {
        InetAddress[] addresses = SsrfProtection.rejectMetadataAddresses(InetAddress.getAllByName(host), host);
        return addresses[0].getHostAddress();
    }

    static boolean isIpLiteral(String host) {
        return host != null && (host.contains(":") || host.matches("[0-9.]+"));
    }

    private static String extractAccountId(String targetGroupArn) {
        if (!AwsArnUtils.looksLikeArn(targetGroupArn)) {
            return null;
        }
        try {
            return AwsArnUtils.parse(targetGroupArn).accountId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
