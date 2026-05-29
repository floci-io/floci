package io.github.hectorvent.floci.services.ecs;

import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsLoadBalancer;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.NetworkBinding;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
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

    @Inject
    public EcsLoadBalancerRegistrar(ElbV2Service elbV2Service, EcsContainerManager containerManager) {
        this.elbV2Service = elbV2Service;
        this.containerManager = containerManager;
    }

    /** Registers the task's load-balanced containers as ELBv2 targets. */
    public void registerTask(EcsTask task, EcsServiceModel svc, String region) {
        forEachTarget(task, svc, (tgArn, td) -> {
            try {
                elbV2Service.registerTargets(region, tgArn, List.of(td));
                LOG.infov("Registered ECS task target {0}:{1} into target group {2}",
                        td.getId(), td.getPort(), tgArn);
            } catch (Exception e) {
                LOG.warnv("Could not register ECS target into {0}: {1}", tgArn, e.getMessage());
            }
        });
    }

    /** Deregisters the task's load-balanced containers from their ELBv2 target groups. */
    public void deregisterTask(EcsTask task, EcsServiceModel svc, String region) {
        forEachTarget(task, svc, (tgArn, td) -> {
            try {
                elbV2Service.deregisterTargets(region, tgArn, List.of(td));
                LOG.infov("Deregistered ECS task target {0}:{1} from target group {2}",
                        td.getId(), td.getPort(), tgArn);
            } catch (Exception e) {
                LOG.warnv("Could not deregister ECS target from {0}: {1}", tgArn, e.getMessage());
            }
        });
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
            td.setPort(binding.hostPort());
            action.accept(lb.getTargetGroupArn(), td);
        }
    }
}
