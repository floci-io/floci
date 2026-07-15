package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.autoscaling.model.MixedInstancesPolicy;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import io.quarkus.runtime.StartupEvent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class AutoScalingReconciler {

    private static final Logger LOG = Logger.getLogger(AutoScalingReconciler.class);

    private final AutoScalingService asgService;
    private final Ec2Service ec2Service;
    private final ElbV2Service elbV2Service;
    private final SsmCommandService ssmCommandService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "asg-reconciler"));

    @Inject
    AutoScalingReconciler(AutoScalingService asgService, Ec2Service ec2Service,
                          ElbV2Service elbV2Service, SsmCommandService ssmCommandService) {
        this.asgService = asgService;
        this.ec2Service = ec2Service;
        this.elbV2Service = elbV2Service;
        this.ssmCommandService = ssmCommandService;
    }

    AutoScalingReconciler(AutoScalingService asgService, Ec2Service ec2Service,
                          ElbV2Service elbV2Service) {
        this(asgService, ec2Service, elbV2Service, null);
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::reconcileAll, 5, 10, TimeUnit.SECONDS);
    }

    void onStart(@Observes StartupEvent event) {
        LOG.debug("Auto Scaling reconciler initialized");
    }

    void reconcileAll() {
        for (AutoScalingGroup asg : asgService.describeAutoScalingGroups(null, null)) {
            try {
                reconcile(asg);
            } catch (Exception e) {
                LOG.warnv("Reconcile failed for ASG {0}: {1}", asg.getAutoScalingGroupName(), e.getMessage());
            }
        }
    }

    public void reconcile(AutoScalingGroup asg) {
        removeTerminatingInstances(asg);
        removeStaleInstances(asg);
        removeOrphanedTargetRegistrations(asg);
        promoteReadyInstances(asg);

        long activeCapacity = activeCapacity(asg);
        int desired = asg.getDesiredCapacity();

        if (activeCapacity < desired) {
            scaleOut(asg, (int) (desired - activeCapacity));
        } else if (activeCapacity > desired) {
            scaleIn(asg, (int) (activeCapacity - desired));
        }
        asgService.saveAutoScalingGroup(asg);
        asgService.completeInstanceRefreshIfSettled(asg.getRegion(), asg.getAutoScalingGroupName());
    }

    static long activeCapacity(AutoScalingGroup asg) {
        return asg.getInstances().stream()
                .filter(i -> {
                    String state = i.getLifecycleState();
                    return "Pending".equals(state) || "InService".equals(state);
                })
                .count();
    }

    private void promoteReadyInstances(AutoScalingGroup asg) {
        boolean changed = false;
        for (AsgInstance asgInst : asg.getInstances()) {
            if (!"Pending".equals(asgInst.getLifecycleState())) {
                continue;
            }
            try {
                List<Instance> ec2Instances = ec2Service
                        .describeInstances(asg.getRegion(), List.of(asgInst.getInstanceId()), null)
                        .stream().flatMap(r -> r.getInstances().stream()).collect(Collectors.toList());
                if (ec2Instances.isEmpty()) {
                    continue;
                }
                String ec2State = ec2Instances.get(0).getState().getName();
                if ("running".equals(ec2State)) {
                    asgInst.setLifecycleState("InService");
                    asgInst.setHealthStatus("Healthy");
                    registerWithTargetGroups(asg, asgInst);
                    changed = true;
                    asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                            "Launching a new EC2 instance: " + asgInst.getInstanceId(),
                            "An instance was started in response to a desired capacity change.",
                            "Successful");
                    LOG.infov("ASG {0}: instance {1} is now InService",
                            asg.getAutoScalingGroupName(), asgInst.getInstanceId());
                }
            } catch (Exception e) {
                LOG.debugv("ASG {0}: could not promote instance {1}: {2}",
                        asg.getAutoScalingGroupName(), asgInst.getInstanceId(), e.getMessage());
            }
        }
        if (changed) {
            asgService.saveAutoScalingGroup(asg);
        }
    }

    private void removeStaleInstances(AutoScalingGroup asg) {
        List<AsgInstance> staleInstances = asg.getInstances().stream()
                .filter(instance -> isStaleInstance(asg, instance))
                .collect(Collectors.toList());
        if (staleInstances.isEmpty()) {
            return;
        }

        List<String> instanceIds = staleInstances.stream()
                .map(AsgInstance::getInstanceId)
                .collect(Collectors.toList());
        failActiveSsmInvocations(asg, instanceIds);
        asg.getInstances().removeIf(instance -> instanceIds.contains(instance.getInstanceId()));
        asgService.saveAutoScalingGroup(asg);
        asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                "Removing stale EC2 instance reference(s): " + instanceIds,
                "Persisted Auto Scaling state referenced instance containers that are no longer running.",
                "Successful");
        LOG.infov("ASG {0}: removed stale instance reference(s) {1}",
                asg.getAutoScalingGroupName(), instanceIds);
    }

    private void failActiveSsmInvocations(AutoScalingGroup asg, List<String> instanceIds) {
        if (ssmCommandService == null) {
            return;
        }
        int failed = ssmCommandService.failActiveInvocationsForInstances(
                asg.getRegion(),
                Set.copyOf(instanceIds),
                "Undeliverable");
        if (failed > 0) {
            LOG.infov("ASG {0}: marked {1} active SSM command invocation(s) failed for stale instances {2}",
                    asg.getAutoScalingGroupName(), failed, instanceIds);
        }
    }

    private boolean isStaleInstance(AutoScalingGroup asg, AsgInstance instance) {
        String lifecycleState = instance.getLifecycleState();
        if ("InService".equals(lifecycleState)) {
            return !ec2Service.isInstanceContainerRunning(instance.getInstanceId());
        }
        if ("Pending".equals(lifecycleState)) {
            return isMissingOrTerminalEc2Instance(asg, instance);
        }
        return false;
    }

    private boolean isMissingOrTerminalEc2Instance(AutoScalingGroup asg, AsgInstance instance) {
        try {
            List<Instance> ec2Instances = ec2Service
                    .describeInstances(asg.getRegion(), List.of(instance.getInstanceId()), null)
                    .stream()
                    .flatMap(r -> r.getInstances().stream())
                    .collect(Collectors.toList());
            if (ec2Instances.isEmpty()) {
                return true;
            }
            String state = ec2Instances.getFirst().getState() != null
                    ? ec2Instances.getFirst().getState().getName()
                    : null;
            return "shutting-down".equals(state)
                    || "terminated".equals(state)
                    || "stopping".equals(state)
                    || "stopped".equals(state);
        }
        catch (Exception e) {
            LOG.debugv("ASG {0}: keeping pending instance {1} during stale check: {2}",
                    asg.getAutoScalingGroupName(), instance.getInstanceId(), e.getMessage());
            return false;
        }
    }

    private void removeOrphanedTargetRegistrations(AutoScalingGroup asg) {
        if (asg.getTargetGroupARNs().isEmpty()) {
            return;
        }

        Set<String> activeInstanceIds = asg.getInstances().stream()
                .filter(instance -> isActiveLifecycleState(instance.getLifecycleState()))
                .map(AsgInstance::getInstanceId)
                .collect(Collectors.toSet());

        for (String tgArn : asg.getTargetGroupARNs()) {
            try {
                List<TargetDescription> orphanedTargets = elbV2Service.describeTargetHealth(
                                asg.getRegion(), tgArn, List.of()).stream()
                        .map(TargetHealth::getTarget)
                        .filter(target -> target != null && target.getId() != null)
                        .filter(target -> !activeInstanceIds.contains(target.getId()))
                        .collect(Collectors.toList());
                if (!orphanedTargets.isEmpty()) {
                    elbV2Service.deregisterTargets(asg.getRegion(), tgArn, orphanedTargets);
                    LOG.infov("ASG {0}: deregistered orphaned target(s) {1} from TG {2}",
                            asg.getAutoScalingGroupName(),
                            orphanedTargets.stream().map(TargetDescription::getId).collect(Collectors.toList()),
                            tgArn);
                }
            } catch (Exception e) {
                LOG.debugv("ASG {0}: could not reconcile TG {1}: {2}",
                        asg.getAutoScalingGroupName(), tgArn, e.getMessage());
            }
        }
    }

    private void removeTerminatingInstances(AutoScalingGroup asg) {
        List<AsgInstance> terminatingInstances = asg.getInstances().stream()
                .filter(instance -> "Terminating".equals(instance.getLifecycleState()))
                .collect(Collectors.toList());
        if (terminatingInstances.isEmpty()) {
            return;
        }

        List<String> instanceIds = terminatingInstances.stream()
                .map(AsgInstance::getInstanceId)
                .collect(Collectors.toList());
        deregisterFromTargetGroups(asg, instanceIds);
        try {
            ec2Service.terminateInstances(asg.getRegion(), instanceIds);
        } catch (Exception e) {
            LOG.warnv("ASG {0}: failed to terminate refreshing instances {1}: {2}",
                    asg.getAutoScalingGroupName(), instanceIds, e.getMessage());
        }

        asg.getInstances().removeIf(instance -> instanceIds.contains(instance.getInstanceId()));
        asgService.saveAutoScalingGroup(asg);
        asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                "Terminating EC2 instance(s) for refresh: " + instanceIds,
                "An instance refresh requested replacement of active instances.",
                "Successful");
        LOG.infov("ASG {0}: terminated instance(s) for refresh {1}",
                asg.getAutoScalingGroupName(), instanceIds);
    }

    private static boolean isActiveLifecycleState(String state) {
        return "Pending".equals(state) || "InService".equals(state);
    }

    private void scaleOut(AutoScalingGroup asg, int count) {
        LaunchSource launchSource = resolveLaunchSource(asg);
        if (launchSource == null) {
            LOG.warnv("ASG {0}: no launch source found, cannot scale out", asg.getAutoScalingGroupName());
            return;
        }
        LOG.infov("ASG {0}: scaling out by {1}", asg.getAutoScalingGroupName(), count);
        String az = asg.getAvailabilityZones().isEmpty()
                ? asg.getRegion() + "a"
                : asg.getAvailabilityZones().get(0);
        String subnetId = asg.getSubnetIds().isEmpty() ? null : asg.getSubnetIds().get(0);
        try {
            Reservation reservation = ec2Service.runInstances(
                    asg.getRegion(),
                    launchSource.imageId(),
                    launchSource.instanceType(),
                    count, count,
                    launchSource.keyName(),
                    launchSource.securityGroupIds(),
                    subnetId,
                    null,
                    propagatedInstanceTags(asg, launchSource),
                    launchSource.userData(),
                    launchSource.iamInstanceProfile());

            for (Instance ec2Inst : reservation.getInstances()) {
                AsgInstance asgInst = new AsgInstance();
                asgInst.setInstanceId(ec2Inst.getInstanceId());
                asgInst.setAvailabilityZone(az);
                asgInst.setLifecycleState("Pending");
                asgInst.setHealthStatus("Healthy");
                asgInst.setLaunchConfigurationName(launchSource.launchConfigurationName());
                asgInst.setLaunchTemplateId(launchSource.launchTemplateId());
                asgInst.setLaunchTemplateName(launchSource.launchTemplateName());
                asgInst.setLaunchTemplateVersion(launchSource.launchTemplateVersion());
                asgInst.setInstanceType(launchSource.instanceType());
                asg.getInstances().add(asgInst);
                LOG.infov("ASG {0}: launched instance {1} (Pending)",
                        asg.getAutoScalingGroupName(), ec2Inst.getInstanceId());
            }
            asgService.saveAutoScalingGroup(asg);
        } catch (Exception e) {
            LOG.warnv("ASG {0}: failed to launch instances: {1}",
                    asg.getAutoScalingGroupName(), e.getMessage());
        }
    }

    private static List<io.github.hectorvent.floci.services.ec2.model.Tag> propagatedInstanceTags(
            AutoScalingGroup asg,
            LaunchSource launchSource) {
        Map<String, String> tags = new LinkedHashMap<>();
        for (io.github.hectorvent.floci.services.ec2.model.Tag tag : launchSource.instanceTags()) {
            tags.put(tag.getKey(), tag.getValue());
        }
        asg.getTags().forEach((key, value) -> {
            if (asg.getTagPropagateAtLaunch().getOrDefault(key, false)) {
                tags.put(key, value);
            }
        });
        return tags.entrySet().stream()
                .map(entry -> new io.github.hectorvent.floci.services.ec2.model.Tag(entry.getKey(), entry.getValue()))
                .toList();
    }

    private void scaleIn(AutoScalingGroup asg, int count) {
        List<AsgInstance> candidates = asg.getInstances().stream()
                .filter(i -> "InService".equals(i.getLifecycleState()))
                .filter(i -> !i.isProtectedFromScaleIn())
                .collect(Collectors.toList());

        List<AsgInstance> toTerminate = candidates.subList(0, Math.min(count, candidates.size()));
        if (toTerminate.isEmpty()) {
            return;
        }
        LOG.infov("ASG {0}: scaling in {1} instance(s)", asg.getAutoScalingGroupName(), toTerminate.size());

        List<String> instanceIds = toTerminate.stream()
                .map(AsgInstance::getInstanceId)
                .collect(Collectors.toList());

        deregisterFromTargetGroups(asg, instanceIds);

        try {
            ec2Service.terminateInstances(asg.getRegion(), instanceIds);
        } catch (Exception e) {
            LOG.warnv("ASG {0}: failed to terminate instances {1}: {2}",
                    asg.getAutoScalingGroupName(), instanceIds, e.getMessage());
        }

        asg.getInstances().removeIf(i -> instanceIds.contains(i.getInstanceId()));
        asgService.saveAutoScalingGroup(asg);
        asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                "Terminating EC2 instance(s): " + instanceIds,
                "An instance was terminated in response to a desired capacity change.",
                "Successful");
    }

    private void deregisterFromTargetGroups(AutoScalingGroup asg, List<String> instanceIds) {
        for (String tgArn : asg.getTargetGroupARNs()) {
            try {
                List<TargetDescription> targets = instanceIds.stream()
                        .map(id -> { TargetDescription td = new TargetDescription(); td.setId(id); return td; })
                        .collect(Collectors.toList());
                elbV2Service.deregisterTargets(asg.getRegion(), tgArn, targets);
            } catch (Exception e) {
                LOG.debugv("ASG {0}: could not deregister from TG {1}: {2}",
                        asg.getAutoScalingGroupName(), tgArn, e.getMessage());
            }
        }
    }

    private void registerWithTargetGroups(AutoScalingGroup asg, AsgInstance asgInst) {
        for (String tgArn : asg.getTargetGroupARNs()) {
            try {
                TargetDescription td = new TargetDescription();
                td.setId(asgInst.getInstanceId());
                elbV2Service.registerTargets(asg.getRegion(), tgArn, List.of(td));
                LOG.debugv("ASG {0}: registered {1} with TG {2}",
                        asg.getAutoScalingGroupName(), asgInst.getInstanceId(), tgArn);
            } catch (Exception e) {
                LOG.warnv("ASG {0}: could not register {1} with TG {2}: {3}",
                        asg.getAutoScalingGroupName(), asgInst.getInstanceId(), tgArn, e.getMessage());
            }
        }
    }

    private LaunchSource resolveLaunchSource(AutoScalingGroup asg) {
        LaunchConfiguration lc = resolveLaunchConfiguration(asg);
        if (lc != null) {
            return new LaunchSource(
                    lc.getLaunchConfigurationName(),
                    lc.getImageId(),
                    lc.getInstanceType(),
                    lc.getKeyName(),
                    lc.getSecurityGroups(),
                    List.of(),
                    lc.getUserData(),
                    lc.getIamInstanceProfile(),
                    null,
                    null,
                    null);
        }

        LaunchTemplate launchTemplate = resolveLaunchTemplate(asg);
        if (launchTemplate != null) {
            LaunchTemplate version = ec2Service.describeLaunchTemplateVersions(
                    asg.getRegion(),
                    launchTemplate.getLaunchTemplateId(),
                    null,
                    asg.getLaunchTemplateVersion() == null ? List.of() : List.of(asg.getLaunchTemplateVersion()))
                    .getFirst();
            String resolvedVersion = version.getLatestVersionNumber() != null
                    ? version.getLatestVersionNumber()
                    : asg.getLaunchTemplateVersion();
            return new LaunchSource(
                    null,
                    version.getImageId(),
                    version.getInstanceType(),
                    version.getKeyName(),
                    version.getSecurityGroupIds(),
                    version.getInstanceTags(),
                    version.getUserData(),
                    version.getIamInstanceProfileArn(),
                    asg.getLaunchTemplateId(),
                    asg.getLaunchTemplateName(),
                    resolvedVersion);
        }

        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                mixedInstancesLaunchTemplateSpecification(asg);
        if (specification != null) {
            LaunchTemplate mixedLaunchTemplate = resolveMixedInstancesLaunchTemplate(asg, specification);
            if (mixedLaunchTemplate != null) {
                LaunchTemplate version = ec2Service.describeLaunchTemplateVersions(
                        asg.getRegion(),
                        mixedLaunchTemplate.getLaunchTemplateId(),
                        null,
                        specification.getVersion() == null ? List.of() : List.of(specification.getVersion()))
                        .getFirst();
                String resolvedVersion = version.getLatestVersionNumber() != null
                        ? version.getLatestVersionNumber()
                        : specification.getVersion();
                String instanceType = mixedInstancesInstanceType(asg, version);
                return new LaunchSource(
                        null,
                        version.getImageId(),
                        instanceType,
                        version.getKeyName(),
                        version.getSecurityGroupIds(),
                        version.getInstanceTags(),
                        version.getUserData(),
                        version.getIamInstanceProfileArn(),
                        specification.getLaunchTemplateId() == null
                                ? mixedLaunchTemplate.getLaunchTemplateId()
                                : specification.getLaunchTemplateId(),
                        specification.getLaunchTemplateName(),
                        resolvedVersion);
            }
        }

        return null;
    }

    private LaunchConfiguration resolveLaunchConfiguration(AutoScalingGroup asg) {
        String lcName = asg.getLaunchConfigurationName();
        if (lcName == null || lcName.isBlank()) {
            return null;
        }
        List<LaunchConfiguration> lcs = asgService.describeLaunchConfigurations(
                asg.getRegion(), List.of(lcName));
        return lcs.isEmpty() ? null : lcs.get(0);
    }

    private LaunchTemplate resolveLaunchTemplate(AutoScalingGroup asg) {
        String ltId = asg.getLaunchTemplateId();
        String ltName = asg.getLaunchTemplateName();
        if ((ltId == null || ltId.isBlank()) && (ltName == null || ltName.isBlank())) {
            return null;
        }
        List<LaunchTemplate> launchTemplates = ec2Service.describeLaunchTemplates(
                asg.getRegion(),
                ltId == null || ltId.isBlank() ? List.of() : List.of(ltId),
                ltName == null || ltName.isBlank() ? List.of() : List.of(ltName),
                Map.of());
        return launchTemplates.isEmpty() ? null : launchTemplates.get(0);
    }

    private MixedInstancesPolicy.LaunchTemplateSpecification mixedInstancesLaunchTemplateSpecification(
            AutoScalingGroup asg) {
        MixedInstancesPolicy policy = asg.getMixedInstancesPolicy();
        if (policy == null || policy.getLaunchTemplate() == null) {
            return null;
        }
        MixedInstancesPolicy.LaunchTemplateSpecification specification =
                policy.getLaunchTemplate().getLaunchTemplateSpecification();
        if (specification == null) {
            return null;
        }
        String ltId = specification.getLaunchTemplateId();
        String ltName = specification.getLaunchTemplateName();
        if ((ltId == null || ltId.isBlank()) && (ltName == null || ltName.isBlank())) {
            return null;
        }
        return specification;
    }

    private LaunchTemplate resolveMixedInstancesLaunchTemplate(
            AutoScalingGroup asg, MixedInstancesPolicy.LaunchTemplateSpecification specification) {
        String ltId = specification.getLaunchTemplateId();
        String ltName = specification.getLaunchTemplateName();
        List<LaunchTemplate> launchTemplates = ec2Service.describeLaunchTemplates(
                asg.getRegion(),
                ltId == null || ltId.isBlank() ? List.of() : List.of(ltId),
                ltName == null || ltName.isBlank() ? List.of() : List.of(ltName),
                Map.of());
        return launchTemplates.isEmpty() ? null : launchTemplates.get(0);
    }

    private String mixedInstancesInstanceType(AutoScalingGroup asg, LaunchTemplate version) {
        MixedInstancesPolicy policy = asg.getMixedInstancesPolicy();
        if (policy != null && policy.getLaunchTemplate() != null) {
            List<MixedInstancesPolicy.LaunchTemplateOverride> overrides =
                    policy.getLaunchTemplate().getOverrides();
            if (overrides != null) {
                for (MixedInstancesPolicy.LaunchTemplateOverride override : overrides) {
                    if (override.getInstanceType() != null && !override.getInstanceType().isBlank()) {
                        return override.getInstanceType();
                    }
                }
            }
        }
        return version.getInstanceType();
    }

    private record LaunchSource(
            String launchConfigurationName,
            String imageId,
            String instanceType,
            String keyName,
            List<String> securityGroupIds,
            List<io.github.hectorvent.floci.services.ec2.model.Tag> instanceTags,
            String userData,
            String iamInstanceProfile,
            String launchTemplateId,
            String launchTemplateName,
            String launchTemplateVersion) {}

    // Override for describeAutoScalingGroups with null region (all regions)
    // The service only filters by region when non-null; null means all.
    // We add a bridge here to avoid changing the service signature.
}
