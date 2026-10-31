package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.autoscaling.model.CapacityReservationSpecification;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefresh;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefreshLaunch;
import io.github.hectorvent.floci.services.autoscaling.model.InstanceRefreshReplacement;
import io.github.hectorvent.floci.services.autoscaling.model.LaunchConfiguration;
import io.github.hectorvent.floci.services.autoscaling.model.MixedInstancesPolicy;
import io.github.hectorvent.floci.services.autoscaling.model.ScalingActivity;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.ec2.Ec2UserDataDecoder;
import io.github.hectorvent.floci.services.ec2.model.Instance;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplate;
import io.github.hectorvent.floci.services.ec2.model.LaunchTemplateData;
import io.github.hectorvent.floci.services.ec2.model.Reservation;
import io.github.hectorvent.floci.services.ec2.model.Subnet;
import io.github.hectorvent.floci.services.ec2.model.Tag;
import io.github.hectorvent.floci.services.elb.ElbClassicService;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetHealth;
import io.github.hectorvent.floci.services.ssm.SsmCommandService;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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
    private static final String SCALE_OUT_CAUSE = "An instance was started in response to a desired capacity change.";

    /** The codes DescribeLaunchTemplates raises for an explicit name or id that does not exist. */
    private static final Set<String> LAUNCH_TEMPLATE_NOT_FOUND_CODES =
            Set.of("InvalidLaunchTemplateName.NotFoundException", "InvalidLaunchTemplateId.NotFound");

    /** EC2 states in which a launch that never came up is given up on. */
    private static final Set<String> ABANDONED_LAUNCH_STATES =
            Set.of("shutting-down", "terminated", "stopping", "stopped");

    /**
     * EC2 states of an instance that is already going away. A stopped instance still exists and
     * still needs terminating, so it is not one of them.
     */
    private static final Set<String> TERMINATED_STATES = Set.of("shutting-down", "terminated");

    /** The error EC2 answers, rather than an empty list, for an instance id it has no record of. */
    private static final String INSTANCE_NOT_FOUND = "InvalidInstanceID.NotFound";

    /** The Activity StatusMessage limit (XmlStringMaxLen255). */
    private static final int STATUS_MESSAGE_MAX_LENGTH = 255;

    private final AutoScalingService asgService;
    private final Ec2Service ec2Service;
    private final ElbV2Service elbV2Service;
    private final ElbClassicService elbClassicService;
    private final SsmCommandService ssmCommandService;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
            r -> new Thread(r, "asg-reconciler"));

    @Inject
    AutoScalingReconciler(AutoScalingService asgService, Ec2Service ec2Service,
                          ElbV2Service elbV2Service, ElbClassicService elbClassicService,
                          SsmCommandService ssmCommandService) {
        this.asgService = asgService;
        this.ec2Service = ec2Service;
        this.elbV2Service = elbV2Service;
        this.elbClassicService = elbClassicService;
        this.ssmCommandService = ssmCommandService;
    }

    AutoScalingReconciler(AutoScalingService asgService, Ec2Service ec2Service,
                          ElbV2Service elbV2Service) {
        this(asgService, ec2Service, elbV2Service, null, null);
    }

    @PostConstruct
    void start() {
        scheduler.scheduleAtFixedRate(this::reconcileAll, 5, 10, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    void onStart(@Observes StartupEvent event) {
        LOG.debug("Auto Scaling reconciler initialized");
    }

    void reconcileAll() {
        Set<String> accountIds;
        try {
            accountIds = asgService.autoScalingGroupAccountIds();
        } catch (Exception e) {
            LOG.warnv("Could not enumerate Auto Scaling group accounts: {0}", e.getMessage());
            return;
        }
        for (String accountId : accountIds) {
            RequestScopes.runAs(accountId, () -> reconcileAccount(accountId));
        }
    }

    private void reconcileAccount(String accountId) {
        try {
            for (AutoScalingGroup asg : asgService.describeAutoScalingGroups(null, null)) {
                try {
                    reconcile(asg);
                } catch (Exception e) {
                    LOG.warnv(e, "Reconcile failed for ASG {0} in account {1}",
                            asg.getAutoScalingGroupName(), accountId);
                }
            }
        } catch (Exception e) {
            LOG.warnv("Could not reconcile Auto Scaling groups in account {0}: {1}", accountId, e.getMessage());
        }
    }

    public void reconcile(AutoScalingGroup asg) {
        asgService.reconcileIfPresent(asg, () -> {
            settleStandbyMoves(asg);
            removeTerminatingInstances(asg);
            removeStaleInstances(asg);
            removeOrphanedTargetRegistrations(asg);
            promoteReadyInstances(asg);
            reconcileRefreshReplacements(asg);

            long activeCapacity = activeCapacity(asg);
            long reservedCapacity = reservedRefreshCapacity(asg);
            int desired = asg.getDesiredCapacity();
            if (activeCapacity + reservedCapacity < desired && !asg.getSuspendedProcesses().contains("Launch")) {
                if (!scaleOut(asg, (int) (desired - activeCapacity - reservedCapacity))) {
                    return;
                }
            } else if (activeCapacity > desired && !asg.getSuspendedProcesses().contains("Terminate")) {
                scaleIn(asg, (int) (activeCapacity - desired));
            }
            if (asgService.saveAutoScalingGroupIfPresent(asg)) {
                asgService.completeInstanceRefreshIfSettled(asg.getRegion(), asg.getAutoScalingGroupName());
            }
        });
    }

    private static boolean refreshPaused(AutoScalingGroup asg) {
        return asg.getSuspendedProcesses().contains("InstanceRefresh")
                || asg.getSuspendedProcesses().contains("Launch")
                || asg.getSuspendedProcesses().contains("Terminate");
    }

    private boolean pausedRefreshOwns(AutoScalingGroup asg, String instanceId) {
        InstanceRefresh refresh = asgService.activeInstanceRefresh(asg);
        return refreshPaused(asg) && refresh != null && refresh.getReplacements() != null
                && refresh.getReplacements().stream()
                .anyMatch(replacement -> instanceId.equals(replacement.getOriginalInstanceId()));
    }

    private long reservedRefreshCapacity(AutoScalingGroup asg) {
        InstanceRefresh refresh = asgService.activeInstanceRefresh(asg);
        if (refresh == null || refresh.getReplacements() == null) {
            return 0;
        }
        return refresh.getReplacements().stream()
                .filter(replacement -> !replacement.isComplete())
                .filter(replacement -> asg.getInstances().stream().noneMatch(instance ->
                        instance.getInstanceId().equals(replacement.getReplacementInstanceId())
                                && isActiveLifecycleState(instance.getLifecycleState())))
                .count();
    }

    private void reconcileRefreshReplacements(AutoScalingGroup asg) {
        InstanceRefresh refresh = asgService.activeInstanceRefresh(asg);
        if (refresh == null || refresh.getReplacements() == null || refreshPaused(asg)) {
            return;
        }
        Map<String, List<Instance>> acceptedLaunches = null;
        for (InstanceRefreshReplacement replacement : refresh.getReplacements()) {
            if (replacement.isComplete()) {
                continue;
            }
            AsgInstance launchedInstance = asg.getInstances().stream()
                    .filter(instance -> instance.getInstanceId().equals(replacement.getReplacementInstanceId()))
                    .findFirst().orElse(null);
            if (launchedInstance != null) {
                if ("InService".equals(launchedInstance.getLifecycleState())) {
                    replacement.setPhase("Complete");
                    asgService.saveInstanceRefresh(refresh);
                }
                continue;
            }
            if (asg.getInstances().stream().anyMatch(instance ->
                    instance.getInstanceId().equals(replacement.getOriginalInstanceId()))) {
                continue;
            }
            if (acceptedLaunches == null) {
                acceptedLaunches = refreshLaunches(asg);
            }
            List<Instance> existing = acceptedLaunches.getOrDefault(replacement.getLaunchClientToken(), List.of());
            if (activeCapacity(asg) >= asg.getDesiredCapacity() && existing.isEmpty()) {
                replacement.setPhase("NoLongerRequired");
                asgService.saveInstanceRefresh(refresh);
                continue;
            }
            replacement.setPhase("AwaitingReplacement");
            asgService.saveInstanceRefresh(refresh);
            List<String> launched = launchInstances(asg, refresh, replacement, existing, acceptedLaunches);
            if (!launched.isEmpty()) {
                replacement.setReplacementInstanceId(launched.getFirst());
                replacement.setPhase("Warming");
                asgService.saveInstanceRefresh(refresh);
            }
        }
    }

    /**
     * Finishes each move into standby: the instance leaves the group's target groups and Classic load
     * balancers, then becomes Standby. A move whose instance left its transient state some other way, such
     * as being terminated or refreshed, is cancelled. A move out of standby is finished by
     * {@link #promoteReadyInstances} once the instance is running.
     */
    private void settleStandbyMoves(AutoScalingGroup asg) {
        List<AsgInstance> entering = new ArrayList<>();
        List<AsgInstance> interrupted = new ArrayList<>();
        for (AsgInstance instance : asg.getInstances()) {
            if ("EnteringStandby".equals(instance.getLifecycleState())) {
                entering.add(instance);
            } else if (instance.getStandbyActivityId() != null && !"Pending".equals(instance.getLifecycleState())) {
                interrupted.add(instance);
            }
        }
        if (entering.isEmpty() && interrupted.isEmpty()) {
            return;
        }
        if (!entering.isEmpty()) {
            List<String> instanceIds = entering.stream().map(AsgInstance::getInstanceId).toList();
            deregisterFromTargetGroups(asg, instanceIds);
            deregisterFromClassicLoadBalancers(asg, instanceIds);
            for (AsgInstance instance : entering) {
                instance.setLifecycleState("Standby");
                if (instance.getStandbyActivityId() != null) {
                    asgService.completeActivity(instance.getStandbyActivityId(), "Successful", null);
                    instance.setStandbyActivityId(null);
                }
            }
            LOG.infov("ASG {0}: instance(s) {1} are now in Standby", asg.getAutoScalingGroupName(), instanceIds);
        }
        asgService.cancelStandbyMoves(interrupted);
        asgService.saveAutoScalingGroupIfPresent(asg);
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
                    registerWithTargetGroups(asg, asgInst);
                    registerWithClassicLoadBalancers(asg, asgInst);
                    changed = true;
                    String standbyActivityId = asgInst.getStandbyActivityId();
                    if (standbyActivityId != null) {
                        // An instance back from standby keeps the health it had there and completes its
                        // ExitStandby activity; nothing was launched.
                        asgInst.setStandbyActivityId(null);
                        asgService.completeActivity(standbyActivityId, "Successful", null);
                    } else {
                        asgInst.setHealthStatus("Healthy");
                        asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                                "Launching a new EC2 instance: " + asgInst.getInstanceId(),
                                SCALE_OUT_CAUSE,
                                "Successful");
                    }
                    LOG.infov("ASG {0}: instance {1} is now InService",
                            asg.getAutoScalingGroupName(), asgInst.getInstanceId());
                }
            } catch (Exception e) {
                LOG.debugv("ASG {0}: could not promote instance {1}: {2}",
                        asg.getAutoScalingGroupName(), asgInst.getInstanceId(), e.getMessage());
            }
        }
        if (changed) {
            asgService.saveAutoScalingGroupIfPresent(asg);
        }
    }

    private void removeStaleInstances(AutoScalingGroup asg) {
        List<AsgInstance> staleInstances = asg.getInstances().stream()
                .filter(instance -> isStaleInstance(asg, instance))
                .collect(Collectors.toList());
        List<AsgInstance> backFromStandby = staleInstances.stream()
                .filter(instance -> instance.getStandbyActivityId() != null)
                .toList();
        if (!backFromStandby.isEmpty()) {
            replaceUnhealthyReturns(asg, backFromStandby);
            staleInstances.removeAll(backFromStandby);
        }
        if (staleInstances.isEmpty()) {
            return;
        }

        List<String> instanceIds = staleInstances.stream()
                .map(AsgInstance::getInstanceId)
                .collect(Collectors.toList());
        failActiveSsmInvocations(asg, instanceIds);
        deregisterFromTargetGroups(asg, instanceIds);
        deregisterFromClassicLoadBalancers(asg, instanceIds);
        asg.getInstances().removeIf(instance -> instanceIds.contains(instance.getInstanceId()));
        asgService.saveAutoScalingGroupIfPresent(asg);
        asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                "Removing stale EC2 instance reference(s): " + instanceIds,
                "Persisted Auto Scaling state referenced instance containers that are no longer running.",
                "Successful");
        LOG.infov("ASG {0}: removed stale instance reference(s) {1}",
                asg.getAutoScalingGroupName(), instanceIds);
    }

    /**
     * Instances back from standby whose EC2 instance stopped or went away fail the health check AWS runs on their
     * return: each is terminated, if EC2 still has it, and leaves the group with AWS's termination activity, and the
     * group launches a replacement for the capacity ExitStandby restored. A termination EC2 refuses is retried on
     * the next pass.
     */
    private void replaceUnhealthyReturns(AutoScalingGroup asg, List<AsgInstance> returning) {
        List<String> instanceIds = returning.stream().map(AsgInstance::getInstanceId).toList();
        List<String> stillThere = instanceIds.stream()
                .filter(id -> !isMissingOrTerminalEc2Instance(asg, id, TERMINATED_STATES))
                .toList();
        if (!stillThere.isEmpty()) {
            try {
                ec2Service.terminateInstances(asg.getRegion(), stillThere);
            } catch (Exception e) {
                LOG.warnv("ASG {0}: could not terminate instance(s) {1} back from standby: {2}",
                        asg.getAutoScalingGroupName(), stillThere, e.getMessage());
                return;
            }
        }
        failActiveSsmInvocations(asg, instanceIds);
        deregisterFromTargetGroups(asg, instanceIds);
        deregisterFromClassicLoadBalancers(asg, instanceIds);
        asgService.cancelStandbyMoves(returning);
        asg.getInstances().removeIf(instance -> instanceIds.contains(instance.getInstanceId()));
        asgService.saveAutoScalingGroupIfPresent(asg);
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        for (String instanceId : instanceIds) {
            asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                    "Terminating EC2 instance: " + instanceId,
                    "At " + now + " an instance was taken out of service in response to an EC2 health check"
                            + " indicating it has been terminated or stopped.",
                    "Successful");
        }
        LOG.infov("ASG {0}: instance(s) {1} back from standby were not running and were replaced",
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
            return isMissingOrTerminalEc2Instance(asg, instance.getInstanceId(), ABANDONED_LAUNCH_STATES);
        }
        if ("Terminating".equals(lifecycleState)) {
            return isMissingOrTerminalEc2Instance(asg, instance.getInstanceId(), TERMINATED_STATES);
        }
        return false;
    }

    /**
     * Whether EC2 has no record of the instance at all, whatever state a record would be in. Any EC2 error other than
     * {@code InvalidInstanceID.NotFound} counts as a record, so the instance is not given up on.
     */
    private boolean hasNoEc2Record(AutoScalingGroup asg, String instanceId) {
        return isMissingOrTerminalEc2Instance(asg, instanceId, Set.of());
    }

    private boolean isMissingOrTerminalEc2Instance(AutoScalingGroup asg, String instanceId,
                                                   Set<String> terminalStates) {
        try {
            List<Instance> ec2Instances = ec2Service
                    .describeInstances(asg.getRegion(), List.of(instanceId), null)
                    .stream()
                    .flatMap(r -> r.getInstances().stream())
                    .collect(Collectors.toList());
            if (ec2Instances.isEmpty()) {
                return true;
            }
            String state = ec2Instances.getFirst().getState() != null
                    ? ec2Instances.getFirst().getState().getName()
                    : null;
            return state != null && terminalStates.contains(state);
        }
        catch (Exception e) {
            if (e instanceof AwsException aws && INSTANCE_NOT_FOUND.equals(aws.getErrorCode())) {
                return true;
            }
            LOG.debugv("ASG {0}: keeping instance {1} during stale check: {2}",
                    asg.getAutoScalingGroupName(), instanceId, e.getMessage());
            return false;
        }
    }

    private void removeOrphanedTargetRegistrations(AutoScalingGroup asg) {
        if (asg.getTargetGroupARNs().isEmpty()) {
            return;
        }

        Set<String> activeInstanceIds = asg.getInstances().stream()
                .filter(instance -> isActiveLifecycleState(instance.getLifecycleState())
                        || pausedRefreshOwns(asg, instance.getInstanceId()))
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
                .filter(instance -> !pausedRefreshOwns(asg, instance.getInstanceId()))
                .collect(Collectors.toList());
        if (terminatingInstances.isEmpty()) {
            return;
        }

        // A terminating instance whose EC2 record is already gone is not a failure: it is pruned as stale by
        // removeStaleInstances in this same pass. It stays out of the batch, which EC2 would stop at its id.
        List<String> instanceIds = terminatingInstances.stream()
                .map(AsgInstance::getInstanceId)
                .filter(id -> !hasNoEc2Record(asg, id))
                .collect(Collectors.toList());
        if (instanceIds.isEmpty()) {
            return;
        }
        try {
            ec2Service.terminateInstances(asg.getRegion(), instanceIds);
        } catch (Exception e) {
            LOG.warnv("ASG {0}: failed to terminate refreshing instances {1}: {2}",
                    asg.getAutoScalingGroupName(), instanceIds, e.getMessage());
            // Only real failures are recorded, not an instance that is going away or has gone in the meantime.
            List<String> stillPresent = instanceIds.stream()
                    .filter(id -> !isMissingOrTerminalEc2Instance(asg, id, TERMINATED_STATES))
                    .toList();
            if (!stillPresent.isEmpty()) {
                recordFailedActivity(asg, "Terminating EC2 instance(s) for refresh: " + stillPresent,
                        "An instance refresh requested replacement of active instances.", e);
            }
            return;
        }

        deregisterFromTargetGroups(asg, instanceIds);
        deregisterFromClassicLoadBalancers(asg, instanceIds);
        asg.getInstances().removeIf(instance -> instanceIds.contains(instance.getInstanceId()));
        asgService.saveAutoScalingGroupIfPresent(asg);
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

    private boolean scaleOut(AutoScalingGroup asg, int count) {
        LaunchSource launchSource = resolveLaunchSource(asg);
        if (launchSource == null) {
            LOG.warnv("ASG {0}: no launch source found, cannot scale out", asg.getAutoScalingGroupName());
            return true;
        }
        LOG.infov("ASG {0}: scaling out by {1}", asg.getAutoScalingGroupName(), count);
        List<String> launchedInstanceIds = new ArrayList<>();
        RuntimeException launchFailure = null;
        try {
            List<LaunchPlacement> placements = new ArrayList<>(launchPlacements(asg));
            for (int index = 0; index < count; index++) {
                LaunchPlacement placement = leastPopulatedPlacement(asg, placements);
                List<Instance> launched = launchInstance(asg, launchSource, placement, null, null,
                        List.of(), launchedInstanceIds, false);
                if (placement.subnetId() != null) {
                    placements.set(placements.indexOf(placement), new LaunchPlacement(placement.subnetId(),
                            placement.availabilityZone(), Math.max(0,
                                    placement.availableIpAddresses() - launched.size()),
                            placement.requiresIpv4Address()));
                }
            }
        } catch (RuntimeException e) {
            launchFailure = e;
        }
        // Preserve successful allocations even if a later single-instance launch fails.
        if (!launchedInstanceIds.isEmpty()) {
            try {
                preserveScaleOutAllocations(asg, launchedInstanceIds);
            } catch (RuntimeException persistenceFailure) {
                if (launchFailure != null) {
                    launchFailure.addSuppressed(persistenceFailure);
                    throw launchFailure;
                }
                throw persistenceFailure;
            }
        }
        if (launchFailure instanceof AwsException) {
            String description = "Launching a new EC2 instance.";
            String message = statusMessage(launchFailure, " Launching EC2 instance failed.");
            recordFailedActivity(asg, description + "  Status Reason: " + message,
                    SCALE_OUT_CAUSE, message, description);
            return false;
        }
        if (launchFailure != null) {
            throw launchFailure;
        }
        return true;
    }

    private void preserveScaleOutAllocations(AutoScalingGroup asg, List<String> launchedInstanceIds) {
        RuntimeException saveFailure = null;
        try {
            if (asgService.saveAutoScalingGroupIfPresent(asg)) {
                return;
            }
        } catch (RuntimeException e) {
            saveFailure = e;
        }
        try {
            ec2Service.terminateInstances(asg.getRegion(), launchedInstanceIds);
            asg.getInstances().removeIf(instance -> launchedInstanceIds.contains(instance.getInstanceId()));
        } catch (RuntimeException cleanupFailure) {
            if (saveFailure == null) {
                throw cleanupFailure;
            }
            saveFailure.addSuppressed(cleanupFailure);
        }
        if (saveFailure != null) {
            throw saveFailure;
        }
    }

    private Map<String, List<Instance>> refreshLaunches(AutoScalingGroup asg) {
        return ec2Service.describeInstances(asg.getRegion(), List.of(), null).stream()
                .flatMap(result -> result.getInstances().stream())
                .filter(instance -> instance.getClientToken() != null)
                .filter(instance -> instance.getState() != null
                        && !ABANDONED_LAUNCH_STATES.contains(instance.getState().getName()))
                .collect(Collectors.groupingBy(Instance::getClientToken));
    }

    private List<String> launchInstances(AutoScalingGroup asg, InstanceRefresh refresh,
                                         InstanceRefreshReplacement replacement, List<Instance> existing,
                                         Map<String, List<Instance>> acceptedLaunches) {
        String clientToken = replacement.getLaunchClientToken();
        LaunchSource launchSource = existing.isEmpty() ? resolveLaunchSource(asg) : null;
        if (existing.isEmpty() && launchSource == null) {
            LOG.warnv("ASG {0}: no launch source found, cannot scale out", asg.getAutoScalingGroupName());
            return List.of();
        }
        List<String> launchedInstanceIds = new ArrayList<>();
        LaunchPlacement placement = existing.isEmpty() ? leastPopulatedPlacement(asg, launchPlacements(asg)) : null;
        InstanceRefreshLaunch metadata = replacement.getLaunch();
        if (existing.isEmpty()) {
            metadata = new InstanceRefreshLaunch(launchSource.launchConfigurationName(),
                    launchSource.launchTemplateId(), launchSource.launchTemplateName(),
                    launchSource.launchTemplateVersion(), launchSource.instanceType(), placement.availabilityZone());
            replacement.setLaunch(metadata);
            asgService.saveInstanceRefresh(refresh);
        }
        List<Instance> launched = launchInstance(asg, launchSource, placement, clientToken, metadata,
                existing, launchedInstanceIds, true);
        if (!launched.isEmpty()) {
            acceptedLaunches.put(clientToken, launched);
            preserveScaleOutAllocations(asg, launchedInstanceIds);
        }
        // Cleanup after a removed group releases these allocations rather than publishing ownership.
        launchedInstanceIds.removeIf(id -> asg.getInstances().stream()
                .noneMatch(instance -> id.equals(instance.getInstanceId())));
        return launchedInstanceIds;
    }

    private Reservation launchWithGroupCapacity(AutoScalingGroup asg, LaunchSource launchSource,
                                                 LaunchPlacement placement, String clientToken) {
        CapacityReservationSpecification groupSpecification = asg.getCapacityReservationSpecification();
        AutoScalingService.validateCapacityReservationSpecification(groupSpecification);
        String preference = groupSpecification == null ? null
                : groupSpecification.getCapacityReservationPreference();
        if (preference == null || "default".equals(preference)) {
            return launchWithCapacity(asg, launchSource, placement, clientToken,
                    launchSource.capacityReservationSpecification());
        }
        LaunchTemplateData.CapacityReservationSpecification specification =
                new LaunchTemplateData.CapacityReservationSpecification();
        if (!"none".equals(preference)) {
            specification.setCapacityReservationTarget(capacityTarget(groupSpecification,
                    launchSource.capacityReservationSpecification()));
        }
        boolean fallback = "capacity-reservations-first".equals(preference);
        specification.setCapacityReservationPreference(fallback
                ? specification.getCapacityReservationTarget() == null ? "open" : "capacity-reservations-only"
                : preference);
        try {
            return launchWithCapacity(asg, launchSource, placement, clientToken, specification);
        } catch (AwsException failure) {
            if (!fallback || !Set.of("InsufficientInstanceCapacity", "ReservationCapacityExceeded")
                    .contains(failure.getErrorCode())) {
                throw failure;
            }
            specification.setCapacityReservationPreference("none");
            specification.setCapacityReservationTarget(null);
            return launchWithCapacity(asg, launchSource, placement, clientToken, specification);
        }
    }

    private static LaunchTemplateData.CapacityReservationTarget capacityTarget(
            CapacityReservationSpecification groupSpecification,
            LaunchTemplateData.CapacityReservationSpecification templateSpecification) {
        if (!groupSpecification.hasTarget()) {
            return templateSpecification == null ? null : templateSpecification.getCapacityReservationTarget();
        }
        LaunchTemplateData.CapacityReservationTarget target = new LaunchTemplateData.CapacityReservationTarget();
        if (!groupSpecification.getCapacityReservationIds().isEmpty()) {
            target.setCapacityReservationId(groupSpecification.getCapacityReservationIds().getFirst());
        } else {
            target.setCapacityReservationResourceGroupArn(
                    groupSpecification.getCapacityReservationResourceGroupArns().getFirst());
        }
        return target;
    }

    private Reservation launchWithCapacity(AutoScalingGroup asg, LaunchSource launchSource,
                                           LaunchPlacement placement, String clientToken,
                                           LaunchTemplateData.CapacityReservationSpecification capacitySpecification) {
        String launchZone = Ec2Service.isModelledAvailabilityZone(asg.getRegion(), placement.availabilityZone())
                ? placement.availabilityZone() : null;
        return ec2Service.runInstances(
                asg.getRegion(), launchSource.imageId(), launchSource.instanceType(), 1, 1,
                launchSource.keyName(), launchSource.securityGroupIds(), placement.subnetId(), clientToken,
                AutoScalingService.propagatedInstanceTags(asg, launchSource.instanceTags()), launchSource.userData(),
                launchSource.launchConfigurationName() == null ? launchSource.iamInstanceProfile()
                        : asgService.resolveLaunchConfigurationProfile(launchSource.iamInstanceProfile()),
                launchSource.associatePublicIpAddress(), null, 0, launchZone, launchSource.metadataOptions(), null,
                launchSource.encodedUserData() != null ? launchSource.encodedUserData()
                        : encodeLegacyUserData(launchSource.userData()),
                false, null, null, launchSource.blockDeviceMappings(), false, null,
                capacitySpecification);
    }

    private List<Instance> launchInstance(AutoScalingGroup asg, LaunchSource launchSource,
                                          LaunchPlacement placement, String clientToken,
                                          InstanceRefreshLaunch metadata, List<Instance> existing,
                                          List<String> launchedInstanceIds, boolean refreshLaunch) {
        List<Instance> instances = existing;
        if (instances.isEmpty()) {
            Reservation reservation;
            try {
                reservation = launchWithGroupCapacity(asg, launchSource, placement, clientToken);
            } catch (RuntimeException launchFailure) {
                if (!refreshLaunch) {
                    throw launchFailure;
                }
                recordFailedActivity(asg, "Launching a replacement EC2 instance.",
                        "An instance refresh requested replacement of active instances.", launchFailure);
                return List.of();
            }
            instances = reservation.getInstances();
        }
        for (Instance instance : instances) {
            launchedInstanceIds.add(instance.getInstanceId());
        }
        for (Instance ec2Inst : instances) {
            if (asg.getInstances().stream().anyMatch(instance ->
                    instance.getInstanceId().equals(ec2Inst.getInstanceId()))) {
                continue;
            }
            AsgInstance asgInst = new AsgInstance();
            asgInst.setInstanceId(ec2Inst.getInstanceId());
            asgInst.setLifecycleState("Pending");
            asgInst.setHealthStatus("Healthy");
            if (metadata != null) {
                asgInst.setAvailabilityZone(metadata.availabilityZone() != null ? metadata.availabilityZone()
                        : ec2Inst.getPlacement() != null ? ec2Inst.getPlacement().getAvailabilityZone() : null);
                asgInst.setLaunchConfigurationName(metadata.launchConfigurationName());
                asgInst.setLaunchTemplateId(metadata.launchTemplateId());
                asgInst.setLaunchTemplateName(metadata.launchTemplateName());
                asgInst.setLaunchTemplateVersion(metadata.launchTemplateVersion());
                asgInst.setInstanceType(metadata.instanceType());
            } else if (launchSource != null) {
                asgInst.setAvailabilityZone(placement.subnetId() == null && placement.availabilityZone() != null
                        ? placement.availabilityZone() : ec2Inst.getPlacement() != null
                        ? ec2Inst.getPlacement().getAvailabilityZone() : placement.availabilityZone());
                asgInst.setLaunchConfigurationName(launchSource.launchConfigurationName());
                asgInst.setLaunchTemplateId(launchSource.launchTemplateId());
                asgInst.setLaunchTemplateName(launchSource.launchTemplateName());
                asgInst.setLaunchTemplateVersion(launchSource.launchTemplateVersion());
                asgInst.setInstanceType(launchSource.instanceType());
            } else {
                // Older accepted attempts have no saved source identity; retain actual EC2 metadata.
                asgInst.setInstanceType(ec2Inst.getInstanceType());
                if (ec2Inst.getPlacement() != null) {
                    asgInst.setAvailabilityZone(ec2Inst.getPlacement().getAvailabilityZone());
                }
            }
            asg.getInstances().add(asgInst);
            LOG.infov("ASG {0}: launched instance {1} (Pending)", asg.getAutoScalingGroupName(), ec2Inst.getInstanceId());
        }
        return instances;
    }

    private List<LaunchPlacement> launchPlacements(AutoScalingGroup asg) {
        if (!asg.getSubnetIds().isEmpty()) {
            Map<String, Subnet> subnets = ec2Service.describeSubnets(asg.getRegion(), asg.getSubnetIds(), Map.of())
                    .stream().collect(Collectors.toMap(Subnet::getSubnetId, subnet -> subnet));
            List<LaunchPlacement> placements = new ArrayList<>();
            for (String id : asg.getSubnetIds()) {
                Subnet subnet = subnets.get(id);
                if (subnet == null) {
                    throw new AwsException("InvalidSubnetID.NotFound", "The subnet ID '" + id + "' does not exist", 400);
                }
                // Subnets remain authoritative when an update leaves older zone metadata behind.
                placements.add(new LaunchPlacement(id, subnet.getAvailabilityZone(), subnet.getAvailableIpAddressCount(),
                        subnet.getCidrBlock() != null && !subnet.getCidrBlock().isBlank()));
            }
            return placements;
        }
        if (!asg.getAvailabilityZones().isEmpty()) {
            return asg.getAvailabilityZones().stream().map(zone -> new LaunchPlacement(null, zone, 0, false)).toList();
        }
        return List.of(new LaunchPlacement(null, null, 0, false));
    }

    private LaunchPlacement leastPopulatedPlacement(AutoScalingGroup asg, List<LaunchPlacement> placements) {
        Map<String, Long> zoneCounts = new LinkedHashMap<>();
        for (LaunchPlacement placement : placements) {
            zoneCounts.putIfAbsent(placement.availabilityZone(), 0L);
        }
        for (AsgInstance instance : asg.getInstances()) {
            if (!isActiveLifecycleState(instance.getLifecycleState())) {
                continue;
            }
            Instance actual = asg.getSubnetIds().isEmpty() ? null
                    : ec2Service.getInstance(asg.getRegion(), instance.getInstanceId()).orElse(null);
            String zone = actual != null && actual.getPlacement() != null
                    ? actual.getPlacement().getAvailabilityZone() : instance.getAvailabilityZone();
            if (zoneCounts.containsKey(zone)) {
                zoneCounts.compute(zone, (ignored, count) -> count + 1);
            }
        }
        Comparator<LaunchPlacement> preference = Comparator
                .comparingLong((LaunchPlacement placement) -> zoneCounts.get(placement.availabilityZone()))
                .thenComparing(Comparator.comparingInt(LaunchPlacement::availableIpAddresses).reversed());
        return placements.stream()
                .filter(placement -> !placement.requiresIpv4Address() || placement.availableIpAddresses() > 0)
                .min(preference)
                .orElseGet(() -> placements.stream().min(preference).orElseThrow());
    }

    private record LaunchPlacement(String subnetId, String availabilityZone, int availableIpAddresses,
                                   boolean requiresIpv4Address) {}

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

        try {
            ec2Service.terminateInstances(asg.getRegion(), instanceIds);
        } catch (Exception e) {
            LOG.warnv("ASG {0}: failed to terminate instances {1}: {2}",
                    asg.getAutoScalingGroupName(), instanceIds, e.getMessage());
            recordFailedActivity(asg, "Terminating EC2 instance(s): " + instanceIds,
                    "An instance was terminated in response to a desired capacity change.", e);
            return;
        }

        deregisterFromTargetGroups(asg, instanceIds);
        deregisterFromClassicLoadBalancers(asg, instanceIds);

        asg.getInstances().removeIf(i -> instanceIds.contains(i.getInstanceId()));
        asgService.saveAutoScalingGroupIfPresent(asg);
        asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                "Terminating EC2 instance(s): " + instanceIds,
                "An instance was terminated in response to a desired capacity change.",
                "Successful");
    }

    /**
     * Records a failed scaling activity with the error text, matching AWS (100% progress and a
     * {@code StatusMessage}). A failure that repeats on a later pass with the same error, because
     * scaling keeps failing, is not recorded again: the latest activity with the same
     * description already records it, whatever other activities were recorded after it. A
     * different error is recorded, so the history carries the current reason.
     */
    private void recordFailedActivity(AutoScalingGroup asg, String description, String cause, Exception failure) {
        recordFailedActivity(asg, description, cause, statusMessage(failure, ""), description);
    }

    private void recordFailedActivity(AutoScalingGroup asg, String description, String cause,
                                      String statusMessage, String descriptionPrefix) {
        ScalingActivity previous = asgService.describeScalingActivities(asg.getRegion(), asg.getAutoScalingGroupName())
                .stream()
                .filter(activity -> activity.getDescription() != null
                        && activity.getDescription().startsWith(descriptionPrefix))
                .findFirst()
                .orElse(null);
        if (previous != null && "Failed".equals(previous.getStatusCode())
                && statusMessage.equals(previous.getStatusMessage())) {
            return;
        }
        ScalingActivity activity = asgService.recordActivity(asg.getRegion(), asg.getAutoScalingGroupName(),
                description, cause, "Failed");
        asgService.completeActivity(activity.getActivityId(), "Failed", statusMessage);
    }

    private static String statusMessage(Exception failure, String suffix) {
        String message = failure.getMessage() != null && !failure.getMessage().isBlank()
                ? failure.getMessage()
                : failure.getClass().getSimpleName();
        int maxLength = STATUS_MESSAGE_MAX_LENGTH - suffix.length();
        if (message.length() <= maxLength) {
            return message + suffix;
        }
        int end = Character.isHighSurrogate(message.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return message.substring(0, end) + suffix;
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

    /**
     * Registers a newly launched instance with every Classic load balancer the group names.
     *
     * <p>An ASG attached through {@code LoadBalancerNames} feeds a Classic load balancer, not a
     * target group, and the two lists are independent — a group can carry either or both. Without
     * this the Classic load balancer would stay empty, and a {@code min_elb_capacity} wait would
     * never be satisfied.
     */
    private void registerWithClassicLoadBalancers(AutoScalingGroup asg, AsgInstance asgInst) {
        if (elbClassicService == null) {
            return;
        }
        for (String lbName : asg.getLoadBalancerNames()) {
            try {
                elbClassicService.registerInstances(asg.getRegion(), lbName,
                        List.of(asgInst.getInstanceId()));
                LOG.debugv("ASG {0}: registered {1} with Classic ELB {2}",
                        asg.getAutoScalingGroupName(), asgInst.getInstanceId(), lbName);
            } catch (Exception e) {
                LOG.warnv("ASG {0}: could not register {1} with Classic ELB {2}: {3}",
                        asg.getAutoScalingGroupName(), asgInst.getInstanceId(), lbName, e.getMessage());
            }
        }
    }

    private void deregisterFromClassicLoadBalancers(AutoScalingGroup asg, List<String> instanceIds) {
        if (elbClassicService == null) {
            return;
        }
        for (String lbName : asg.getLoadBalancerNames()) {
            try {
                elbClassicService.deregisterInstances(asg.getRegion(), lbName, instanceIds);
            } catch (Exception e) {
                LOG.debugv("ASG {0}: could not deregister from Classic ELB {1}: {2}",
                        asg.getAutoScalingGroupName(), lbName, e.getMessage());
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
            String userData = lc.getUserData();
            if (Boolean.TRUE.equals(lc.getUserDataBase64Encoded())) {
                try {
                    userData = Ec2UserDataDecoder.decode(userData);
                } catch (AwsException e) {
                    LOG.warnv("ASG {0}: cannot decode user data for launch configuration {1}: {2}",
                            asg.getAutoScalingGroupName(), lc.getLaunchConfigurationName(), e.getMessage());
                    return null;
                }
            }
            return new LaunchSource(
                    lc.getLaunchConfigurationName(),
                    lc.getImageId(),
                    lc.getInstanceType(),
                    lc.getKeyName(),
                    lc.getSecurityGroups(),
                    List.of(),
                    userData,
                    Boolean.TRUE.equals(lc.getUserDataBase64Encoded()) ? lc.getUserData() : null,
                    lc.getIamInstanceProfile(),
                    null,
                    null,
                    null,
                    // Forwarded as-is: an explicit false has to beat a public
                    // subnet's MapPublicIpOnLaunch, and only null means
                    // "fall back to the subnet default".
                    lc.getAssociatePublicIpAddress(),
                    null, lc.getMetadataOptions(), null);
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
                    version.getData().getImageId(),
                    version.getData().getInstanceType(),
                    version.getData().getKeyName(),
                    version.getData().effectiveSecurityGroupIds(),
                    version.getData().getInstanceTags(),
                    Ec2UserDataDecoder.decodeIfMissing(
                            version.getData().getUserData(), version.getData().getEncodedUserData()),
                    version.getData().getEncodedUserData(),
                    ec2Service.iamInstanceProfileArn(version.getData()),
                    asg.getLaunchTemplateId(),
                    asg.getLaunchTemplateName(),
                    resolvedVersion,
                    version.getData().effectiveAssociatePublicIpAddress(),
                    version.getData().getBlockDeviceMappings(), version.getData().getMetadataOptions(),
                    version.getData().getCapacityReservationSpecification());
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
                        version.getData().getImageId(),
                        instanceType,
                        version.getData().getKeyName(),
                        version.getData().effectiveSecurityGroupIds(),
                        version.getData().getInstanceTags(),
                        Ec2UserDataDecoder.decodeIfMissing(
                                version.getData().getUserData(), version.getData().getEncodedUserData()),
                        version.getData().getEncodedUserData(),
                        ec2Service.iamInstanceProfileArn(version.getData()),
                        specification.getLaunchTemplateId() == null
                                ? mixedLaunchTemplate.getLaunchTemplateId()
                                : specification.getLaunchTemplateId(),
                        specification.getLaunchTemplateName(),
                        resolvedVersion,
                        version.getData().effectiveAssociatePublicIpAddress(),
                        version.getData().getBlockDeviceMappings(), version.getData().getMetadataOptions(),
                    version.getData().getCapacityReservationSpecification());
            }
        }

        return null;
    }

    private static String encodeLegacyUserData(String userData) {
        return userData == null ? null : Base64.getEncoder().encodeToString(userData.getBytes(StandardCharsets.UTF_8));
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
        return lookupLaunchTemplate(asg, ltId, ltName);
    }

    /**
     * The launch template a group points at, or null when it is gone. DescribeLaunchTemplates
     * reports an explicitly requested name or id that does not exist as an error, which must not
     * abort a reconcile pass: a group whose template was deleted simply has nothing to launch from.
     * Only those NotFound codes read as "gone"; any other failure propagates rather than quietly
     * skipping the group's scaling.
     */
    private LaunchTemplate lookupLaunchTemplate(AutoScalingGroup asg, String ltId, String ltName) {
        try {
            List<LaunchTemplate> launchTemplates = ec2Service.describeLaunchTemplates(
                    asg.getRegion(),
                    ltId == null || ltId.isBlank() ? List.of() : List.of(ltId),
                    ltName == null || ltName.isBlank() ? List.of() : List.of(ltName),
                    Map.of());
            return launchTemplates.isEmpty() ? null : launchTemplates.get(0);
        } catch (AwsException e) {
            if (!LAUNCH_TEMPLATE_NOT_FOUND_CODES.contains(e.getErrorCode())) {
                throw e;
            }
            LOG.debugv("ASG {0}: launch template {1} is gone: {2}",
                    asg.getAutoScalingGroupName(),
                    ltId == null || ltId.isBlank() ? ltName : ltId,
                    e.getMessage());
            return null;
        }
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
        return lookupLaunchTemplate(
                asg, specification.getLaunchTemplateId(), specification.getLaunchTemplateName());
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
        return version.getData().getInstanceType();
    }

    private record LaunchSource(
            String launchConfigurationName,
            String imageId,
            String instanceType,
            String keyName,
            List<String> securityGroupIds,
            List<Tag> instanceTags,
            String userData,
            String encodedUserData,
            String iamInstanceProfile,
            String launchTemplateId,
            String launchTemplateName,
            String launchTemplateVersion,
            Boolean associatePublicIpAddress,
            List<LaunchTemplateData.BlockDeviceMapping> blockDeviceMappings,
            LaunchTemplateData.MetadataOptions metadataOptions,
            LaunchTemplateData.CapacityReservationSpecification capacityReservationSpecification) {}

    // Override for describeAutoScalingGroups with null region (all regions)
    // The service only filters by region when non-null; null means all.
    // We add a bridge here to avoid changing the service signature.
}
