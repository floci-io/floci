package io.github.hectorvent.floci.services.applicationautoscaling;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.ecs.EcsService;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * {@link CapacityAdjuster} for {@code ecs:service:DesiredCount} — the only scalable
 * dimension this control loop drives today.
 */
@ApplicationScoped
public class EcsCapacityAdjuster implements CapacityAdjuster {

    private final EcsService ecsService;

    @Inject
    public EcsCapacityAdjuster(EcsService ecsService) {
        this.ecsService = ecsService;
    }

    @Override
    public boolean supports(String scalableDimension) {
        return "ecs:service:DesiredCount".equals(scalableDimension);
    }

    @Override
    public int getCurrentCapacity(String resourceId, String region) {
        return resolveService(resourceId, region).getDesiredCount();
    }

    @Override
    public void setCapacity(String resourceId, int capacity, String region) {
        ClusterAndService ref = parse(resourceId);
        ecsService.updateService(ref.cluster(), ref.service(), null, capacity, null, region);
    }

    private EcsServiceModel resolveService(String resourceId, String region) {
        ClusterAndService ref = parse(resourceId);
        List<EcsServiceModel> found = ecsService.describeServices(ref.cluster(), List.of(ref.service()), region);
        if (found.isEmpty()) {
            throw new AwsException("ObjectNotFoundException",
                    "No ECS service found for resource ID: " + resourceId, 400);
        }
        return found.get(0);
    }

    /** Application Auto Scaling's ECS {@code resourceId} is {@code service/<cluster>/<service>}. */
    private static ClusterAndService parse(String resourceId) {
        String[] parts = resourceId == null ? new String[0] : resourceId.split("/");
        if (parts.length != 3 || !"service".equals(parts[0])) {
            throw new AwsException("ValidationException", "Invalid ECS resource ID: " + resourceId, 400);
        }
        return new ClusterAndService(parts[1], parts[2]);
    }

    private record ClusterAndService(String cluster, String service) {}
}
