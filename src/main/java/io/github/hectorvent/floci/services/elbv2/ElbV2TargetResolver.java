package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.SsrfProtection;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;

import java.io.IOException;
import java.net.InetAddress;

final class ElbV2TargetResolver {

    private ElbV2TargetResolver() {}

    static String resolveHost(Ec2Service ec2Service, TargetGroup targetGroup, TargetDescription target) {
        return resolveHost(ec2Service, targetGroup, target != null ? target.getId() : null);
    }

    static String resolveHost(Ec2Service ec2Service, TargetGroup targetGroup, String targetId) {
        if (targetId == null || targetId.isBlank()) {
            return targetId;
        }
        if (targetGroup == null || !"instance".equals(targetGroup.getTargetType()) || ec2Service == null) {
            return targetId;
        }

        String accountId = extractAccountId(targetGroup.getTargetGroupArn());
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
        if (targetGroupArn == null || !targetGroupArn.startsWith("arn:")) {
            return null;
        }
        try {
            return AwsArnUtils.parse(targetGroupArn).accountId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
