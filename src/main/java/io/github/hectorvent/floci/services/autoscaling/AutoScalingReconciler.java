package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.model.BlockDevice;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
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
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "asg-reconciler"));

    @Inject
    AutoScalingReconciler(AutoScalingService asgService, Ec2Service ec2Service,
                          ElbV2Service elbV2Service) {
        this.asgService = asgService;
        this.ec2Service = ec2Service;
        this.elbV2Service = elbV2Service;
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::reconcileAll, 5, 10, TimeUnit.SECONDS);
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
        promoteReadyInstances(asg);

        long inService = asg.getInstances().stream()
                .filter(i -> "InService".equals(i.getLifecycleState()))
                .count();
        int desired = asg.getDesiredCapacity();

        if (inService < desired) {
            scaleOut(asg, (int) (desired - inService));
        } else if (inService > desired) {
            scaleIn(asg, (int) (inService - desired));
        }
    }

    private void promoteReadyInstances(AutoScalingGroup asg) {
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
    }

    private void scaleOut(AutoScalingGroup asg, int count) {
        LaunchConfiguration lc = resolveLaunchConfiguration(asg);
        if (lc == null) {
            LOG.warnv("ASG {0}: no launch configuration found, cannot scale out", asg.getAutoScalingGroupName());
            return;
        }
        LOG.infov("ASG {0}: scaling out by {1}", asg.getAutoScalingGroupName(), count);
        String az = asg.getAvailabilityZones().isEmpty()
                ? asg.getRegion() + "a"
                : asg.getAvailabilityZones().get(0);
        try {
            Reservation reservation = ec2Service.runInstances(
                    asg.getRegion(),
                    lc.getImageId(),
                    lc.getInstanceType(),
                    count, count,
                    lc.getKeyName(),
                    lc.getSecurityGroups(),
                    null,
                    null,
                    null,
                    lc.getUserData(),
                    lc.getIamInstanceProfile(),
                    List.of(new BlockDevice())
            );

            for (Instance ec2Inst : reservation.getInstances()) {
                AsgInstance asgInst = new AsgInstance();
                asgInst.setInstanceId(ec2Inst.getInstanceId());
                asgInst.setAvailabilityZone(az);
                asgInst.setLifecycleState("Pending");
                asgInst.setHealthStatus("Healthy");
                asgInst.setLaunchConfigurationName(lc.getLaunchConfigurationName());
                asgInst.setInstanceType(lc.getInstanceType());
                asg.getInstances().add(asgInst);
                LOG.infov("ASG {0}: launched instance {1} (Pending)",
                        asg.getAutoScalingGroupName(), ec2Inst.getInstanceId());
            }
        } catch (Exception e) {
            LOG.warnv("ASG {0}: failed to launch instances: {1}",
                    asg.getAutoScalingGroupName(), e.getMessage());
        }
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

        // Deregister from all attached target groups first
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

        try {
            ec2Service.terminateInstances(asg.getRegion(), instanceIds);
        } catch (Exception e) {
            LOG.warnv("ASG {0}: failed to terminate instances {1}: {2}",
                    asg.getAutoScalingGroupName(), instanceIds, e.getMessage());
        }

        asg.getInstances().removeIf(i -> instanceIds.contains(i.getInstanceId()));
        asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                "Terminating EC2 instance(s): " + instanceIds,
                "An instance was terminated in response to a desired capacity change.",
                "Successful");
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

    private LaunchConfiguration resolveLaunchConfiguration(AutoScalingGroup asg) {
        String lcName = asg.getLaunchConfigurationName();
        if (lcName == null || lcName.isBlank()) {
            return null;
        }
        List<LaunchConfiguration> lcs = asgService.describeLaunchConfigurations(
                asg.getRegion(), List.of(lcName));
        return lcs.isEmpty() ? null : lcs.get(0);
    }

    // Override for describeAutoScalingGroups with null region (all regions)
    // The service only filters by region when non-null; null means all.
    // We add a bridge here to avoid changing the service signature.
}
