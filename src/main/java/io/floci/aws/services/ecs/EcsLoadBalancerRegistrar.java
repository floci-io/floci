package io.floci.aws.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.aws.core.common.RequestScopes;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.ecs.container.EcsContainerManager;
import io.floci.aws.services.ecs.model.Container;
import io.floci.aws.services.ecs.model.EcsLoadBalancer;
import io.floci.aws.services.ecs.model.EcsRegisteredTargets;
import io.floci.aws.services.ecs.model.EcsServiceModel;
import io.floci.aws.services.ecs.model.EcsTask;
import io.floci.aws.services.ecs.model.EcsTaskAddress;
import io.floci.aws.services.ecs.model.NetworkBinding;
import io.floci.aws.services.elbv2.ElbV2Service;
import io.floci.aws.services.elbv2.model.TargetDescription;
import io.floci.aws.services.elbv2.model.TargetHealth;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Bridges ECS services to ELBv2: when an ECS service declares a {@code loadBalancers}
 * block, this registrar registers each running task container as a target in the named
 * ELBv2 target group (and deregisters it when the task stops).
 * <p>
 * One-way dependency ECS → ELBv2; the ELBv2 data plane reaches the container over plain
 * TCP and never calls back into ECS, so there is no cycle.
 */
@ApplicationScoped
public class EcsLoadBalancerRegistrar {

    private static final Logger LOG = Logger.getLogger(EcsLoadBalancerRegistrar.class);

    private final ElbV2Service elbV2Service;
    private final EcsContainerManager containerManager;
    // taskArn → the targets registered for it, persisted so they can be released after a restart
    private final AccountAwareStorageBackend<EcsRegisteredTargets> ledger;

    @Inject
    public EcsLoadBalancerRegistrar(ElbV2Service elbV2Service, EcsContainerManager containerManager,
                                    StorageFactory storageFactory) {
        this.elbV2Service = elbV2Service;
        this.containerManager = containerManager;
        this.ledger = storageFactory.create("ecs", "ecs-registered-targets.json",
                new TypeReference<Map<String, EcsRegisteredTargets>>() {});
    }

    /**
     * Registers the task's load-balanced containers as ELBv2 targets. The targets are recorded
     * before they are registered, so a process killed in between still leaves a record for the
     * next run to release. A target already in its group is recorded too: its address and port
     * are the task's own container, so it reaches nothing else, and once the task stops it would
     * reach nothing or whichever container Docker hands the address to next. ECS on AWS likewise
     * deregisters a stopping task's target whoever registered it.
     */
    public void registerTask(EcsTask task, EcsServiceModel svc, String region) {
        List<EcsRegisteredTargets.Target> planned = new ArrayList<>();
        forEachTarget(task, svc, (tgArn, td) ->
                planned.add(new EcsRegisteredTargets.Target(tgArn, td.getId(), td.getPort())));
        if (planned.isEmpty()) {
            return;
        }
        String taskArn = task.getTaskArn();
        if (taskArn != null) {
            ledger.put(taskArn, new EcsRegisteredTargets(region, List.copyOf(planned)));
        }
        List<EcsRegisteredTargets.Target> registered = new ArrayList<>();
        for (EcsRegisteredTargets.Target target : planned) {
            try {
                elbV2Service.registerTargets(region, target.targetGroupArn(), List.of(target.toDescription()));
                registered.add(target);
                LOG.infov("Registered ECS task target {0}:{1} into target group {2}",
                        target.id(), target.port(), target.targetGroupArn());
            } catch (Exception e) {
                LOG.warnv("Could not register ECS target into {0}: {1}", target.targetGroupArn(), e.getMessage());
            }
        }
        if (taskArn == null || registered.size() == planned.size()) {
            return;
        }
        if (registered.isEmpty()) {
            ledger.delete(taskArn);
        } else {
            ledger.put(taskArn, new EcsRegisteredTargets(region, List.copyOf(registered)));
        }
    }

    /**
     * Deregisters the targets {@link #registerTask} recorded for the task, and no other: a task
     * with no record registered nothing, as when none of its containers had a reachable binding.
     * Only a task without an ARN, which {@link #registerTask} cannot record, falls back to the
     * targets its containers resolve to now.
     *
     * @return how long the deregistered targets drain: the longest
     *         {@code deregistration_delay.timeout_seconds} of their target groups, during which
     *         AWS keeps the task running for the requests already in flight
     */
    public Duration deregisterTask(EcsTask task, EcsServiceModel svc, String region) {
        List<Duration> drains = new ArrayList<>();
        if (task.getTaskArn() == null) {
            forEachTarget(task, svc, (tgArn, td) -> drains.add(deregister(region, tgArn, td)));
        } else {
            Optional<EcsRegisteredTargets> recorded = ledger.get(task.getTaskArn());
            if (recorded.isPresent()) {
                drains.addAll(deregister(recorded.get()));
                ledger.delete(task.getTaskArn());
            }
        }
        return drains.stream().max(Comparator.naturalOrder()).orElse(Duration.ZERO);
    }

    /**
     * Whether every target {@link #registerTask} recorded for the task reports {@code healthy}.
     * A task that registered nothing, or whose target group is gone, has nothing to wait for.
     */
    public boolean targetsHealthy(EcsTask task) {
        Optional<EcsRegisteredTargets> recorded = task.getTaskArn() == null
                ? Optional.empty() : ledger.get(task.getTaskArn());
        if (recorded.isEmpty() || recorded.get().targets() == null) {
            return true;
        }
        for (EcsRegisteredTargets.Target target : recorded.get().targets()) {
            try {
                List<TargetHealth> health = elbV2Service.describeTargetHealth(recorded.get().region(),
                        target.targetGroupArn(), List.of(target.toDescription()));
                if (health.stream().anyMatch(th -> !"healthy".equals(th.getState()))) {
                    return false;
                }
            } catch (Exception e) {
                LOG.debugv("Could not read the health of {0}: {1}", target.targetGroupArn(), e.getMessage());
            }
        }
        return true;
    }

    /**
     * Deregisters every target ECS recorded for a task, in every account. Only for startup, when
     * ECS holds no task at all: task state is memory-only, so each recorded target belongs to a
     * task of a previous run whose container is gone or is being removed, and Docker is free to
     * hand its address to an unrelated container. Targets ECS did not register are never touched.
     */
    public void releaseRecordedTargets() {
        for (AccountAwareStorageBackend.AccountEntry<EcsRegisteredTargets> entry
                : ledger.scanAllAccountEntries(key -> true)) {
            RequestScopes.runAs(entry.accountId(), () -> deregister(entry.value()));
            ledger.deleteForAccount(entry.accountId(), entry.key());
            LOG.infov("Released the load balancer targets of ECS task {0} left by a previous run", entry.key());
        }
    }

    /**
     * Deregisters, from the given target groups, each target at one of a starting task's addresses
     * and ports that no ECS task recorded. The address was free until this task took it, so such a target
     * was left by a task that is gone, as one registered by a Floci version that kept no record,
     * and would now send the group's traffic to this task, which belongs to some other service.
     * A target a live task recorded stays, whichever group it is in.
     */
    public void evictUnrecordedTargets(EcsTask task, Collection<String> targetGroupArns,
                                       Collection<EcsTaskAddress> addresses, String region) {
        if (targetGroupArns.isEmpty() || addresses.isEmpty()) {
            return;
        }
        Set<EcsRegisteredTargets.Target> recorded = new HashSet<>();
        for (EcsRegisteredTargets entry : ledger.scan(key -> true)) {
            if (entry.targets() != null) {
                recorded.addAll(entry.targets());
            }
        }
        for (String tgArn : targetGroupArns) {
            List<TargetHealth> health;
            try {
                health = elbV2Service.describeTargetHealth(region, tgArn, null);
            } catch (Exception e) {
                // A target group deleted while a service still names it: nothing in it to evict.
                LOG.debugv("Could not list the targets of {0}: {1}", tgArn, e.getMessage());
                continue;
            }
            for (TargetHealth th : health) {
                TargetDescription td = th.getTarget();
                boolean atTaskAddress = addresses.stream().anyMatch(a -> a.matches(td.getId(), td.getPort()));
                if (!atTaskAddress
                        || recorded.contains(new EcsRegisteredTargets.Target(tgArn, td.getId(), td.getPort()))) {
                    continue;
                }
                try {
                    elbV2Service.deregisterTargets(region, tgArn, List.of(td));
                    LOG.warnv("Deregistered stale target {0}:{1} from target group {2}: no ECS task registered it,"
                            + " and its address now belongs to ECS task {3}", td.getId(), td.getPort(), tgArn,
                            task.getTaskArn());
                } catch (Exception e) {
                    LOG.warnv("Could not deregister stale target {0}:{1} from {2}: {3}", td.getId(), td.getPort(),
                            tgArn, e.getMessage());
                }
            }
        }
    }

    private List<Duration> deregister(EcsRegisteredTargets recorded) {
        List<Duration> drains = new ArrayList<>();
        if (recorded.targets() == null) {
            return drains;
        }
        for (EcsRegisteredTargets.Target target : recorded.targets()) {
            drains.add(deregister(recorded.region(), target.targetGroupArn(), target.toDescription()));
        }
        return drains;
    }

    /** @return the target group's deregistration delay, or zero when the target could not be deregistered */
    private Duration deregister(String region, String tgArn, TargetDescription td) {
        try {
            elbV2Service.deregisterTargets(region, tgArn, List.of(td));
            LOG.infov("Deregistered ECS task target {0}:{1} from target group {2}",
                    td.getId(), td.getPort(), tgArn);
        } catch (Exception e) {
            LOG.warnv("Could not deregister ECS target from {0}: {1}", tgArn, e.getMessage());
            return Duration.ZERO;
        }
        String delay = elbV2Service.describeTargetGroupAttributes(region, tgArn)
                .get("deregistration_delay.timeout_seconds");
        try {
            return delay == null ? Duration.ZERO : Duration.ofSeconds(Long.parseLong(delay));
        } catch (NumberFormatException e) {
            LOG.warnv("Ignoring the deregistration delay {0} of target group {1}: not a number", delay, tgArn);
            return Duration.ZERO;
        }
    }

    private void forEachTarget(EcsTask task, EcsServiceModel svc,
                               BiConsumer<String, TargetDescription> action) {
        if (svc == null || svc.getLoadBalancers() == null || svc.getLoadBalancers().isEmpty()) {
            return;
        }
        if (task.getContainers() == null || task.getContainers().isEmpty()) {
            return;
        }
        for (EcsLoadBalancer lb : svc.getLoadBalancers()) {
            if (lb.getTargetGroupArn() == null || lb.getTargetGroupArn().isBlank()) {
                continue;
            }
            Container container = task.getContainers().stream()
                    .filter(c -> lb.getContainerName() == null
                            || lb.getContainerName().equals(c.getName()))
                    .findFirst()
                    .orElse(null);
            if (container == null || container.getNetworkBindings() == null) {
                continue;
            }
            NetworkBinding binding = container.getNetworkBindings().stream()
                    .filter(b -> lb.getContainerPort() == null
                            || lb.getContainerPort() == b.containerPort())
                    .findFirst()
                    .orElse(null);
            if (binding == null) {
                continue;
            }
            TargetDescription td = new TargetDescription();
            td.setId(containerManager.resolveContainerHost(container));
            td.setPort(EcsTaskAddress.isLoopback(td.getId()) ? binding.hostPort() : binding.containerPort());
            action.accept(lb.getTargetGroupArn(), td);
        }
    }
}
