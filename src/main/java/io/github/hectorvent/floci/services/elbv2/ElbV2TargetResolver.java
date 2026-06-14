package io.github.hectorvent.floci.services.elbv2;

import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;

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

        Instance instance = ec2Service.findInstanceById(targetId);
        if (instance == null) {
            return targetId;
        }

        String containerBridgeIp = instance.getContainerBridgeIp();
        if (containerBridgeIp != null && !containerBridgeIp.isBlank()) {
            return containerBridgeIp;
        }
        return targetId;
    }
}
