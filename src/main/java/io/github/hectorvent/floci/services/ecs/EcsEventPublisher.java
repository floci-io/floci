package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.ecs.model.Container;
import io.github.hectorvent.floci.services.ecs.model.EcsServiceModel;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.TaskStatus;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Publishes AWS-shaped {@code aws.ecs} lifecycle events to the default EventBridge bus.
 *
 * <p>Floci's task model only ever occupies PENDING, RUNNING and STOPPED. AWS reports a
 * fuller phase ladder, and EventBridge rules filter on {@code detail.lastStatus}, so the
 * intermediate phases are <em>synthesized</em> here: one event per phase, each carrying the
 * task's real fields with only {@code lastStatus} varied. The task object is restored to its
 * caller-visible status before this method returns.
 */
@ApplicationScoped
public class EcsEventPublisher {

    private static final Logger LOG = Logger.getLogger(EcsEventPublisher.class);
    private static final String SOURCE = "aws.ecs";
    private static final String TASK_DETAIL_TYPE = "ECS Task State Change";
    private static final String DEPLOYMENT_DETAIL_TYPE = "ECS Deployment State Change";

    private static final List<TaskStatus> START_LADDER =
            List.of(TaskStatus.PROVISIONING, TaskStatus.PENDING, TaskStatus.ACTIVATING, TaskStatus.RUNNING);
    private static final List<TaskStatus> STOP_LADDER =
            List.of(TaskStatus.DEACTIVATING, TaskStatus.STOPPING, TaskStatus.DEPROVISIONING, TaskStatus.STOPPED);

    private final EventBridgeService eventBridgeService;
    private final RegionResolver regionResolver;
    private final ObjectMapper objectMapper;

    @Inject
    public EcsEventPublisher(EventBridgeService eventBridgeService,
                             RegionResolver regionResolver,
                             ObjectMapper objectMapper) {
        this.eventBridgeService = eventBridgeService;
        this.regionResolver = regionResolver;
        this.objectMapper = objectMapper;
    }

    /** Emits the synthesized phase sequence for a start (to RUNNING) or a stop (to STOPPED). */
    public void emitTaskLadder(EcsTask task, TaskStatus from, TaskStatus to, String region) {
        List<TaskStatus> ladder = to == TaskStatus.STOPPED ? STOP_LADDER : START_LADDER;
        // Synthesize the ladder on a snapshot, not the shared task instance: that instance lives
        // in EcsService's live task map and stays visible to a concurrent DescribeTasks/ListTasks
        // call for the whole synthesis, which would otherwise observe a lastStatus this task never
        // actually occupies.
        EcsTask snapshot = new EcsTask(task);
        for (TaskStatus phase : ladder) {
            snapshot.setLastStatus(phase.name());
            emitTaskStateChange(snapshot, region);
        }
    }

    /** Emits one ECS Task State Change reflecting the task's current fields. */
    public void emitTaskStateChange(EcsTask task, String region) {
        try {
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("clusterArn", task.getClusterArn());
            detail.put("taskArn", task.getTaskArn());
            detail.put("lastStatus", task.getLastStatus());
            detail.put("desiredStatus", task.getDesiredStatus());
            detail.put("taskDefinitionArn", task.getTaskDefinitionArn());
            detail.put("group", task.getGroup());
            detail.put("startedBy", task.getStartedBy());
            detail.put("launchType", task.getLaunchType() != null ? task.getLaunchType().name() : null);
            detail.put("createdAt", asIso(task.getCreatedAt()));
            detail.put("startedAt", asIso(task.getStartedAt()));
            detail.put("stoppedAt", asIso(task.getStoppedAt()));
            detail.put("stoppedReason", task.getStoppedReason());
            detail.put("updatedAt", Instant.now().toString());
            ArrayNode containers = detail.putArray("containers");
            if (task.getContainers() != null) {
                for (Container c : task.getContainers()) {
                    ObjectNode cn = containers.addObject();
                    cn.put("name", c.getName());
                    cn.put("lastStatus", c.getLastStatus());
                    cn.put("image", c.getImage());
                    if (c.getExitCode() != null) {
                        cn.put("exitCode", c.getExitCode());
                    }
                }
            }
            putEvent(TASK_DETAIL_TYPE, task.getTaskArn(), detail, region);
        } catch (Exception e) {
            LOG.warnv("Failed to emit ECS Task State Change for {0}: {1}", task.getTaskArn(), e.getMessage());
        }
    }

    /** Emits one ECS Deployment State Change (eventType INFO). */
    public void emitDeploymentStateChange(EcsServiceModel svc, String eventName, String reason, String region) {
        try {
            ObjectNode detail = objectMapper.createObjectNode();
            detail.put("eventType", "INFO");
            detail.put("eventName", eventName);
            detail.put("deploymentId", svc.getDeploymentId());
            detail.put("updatedAt", Instant.now().toString());
            detail.put("reason", reason);
            putEvent(DEPLOYMENT_DETAIL_TYPE, svc.getServiceArn(), detail, region);
        } catch (Exception e) {
            LOG.warnv("Failed to emit ECS Deployment State Change for {0}: {1}",
                    svc.getServiceArn(), e.getMessage());
        }
    }

    private void putEvent(String detailType, String resourceArn, ObjectNode detail, String region)
            throws Exception {
        ArrayNode resources = objectMapper.createArrayNode();
        if (resourceArn != null) {
            resources.add(resourceArn);
        }
        Map<String, Object> entry = new HashMap<>();
        entry.put("Source", SOURCE);
        entry.put("DetailType", detailType);
        entry.put("Detail", objectMapper.writeValueAsString(detail));
        entry.put("Resources", resources);
        eventBridgeService.putEvents(List.of(entry), effectiveRegion(region));
    }

    private String effectiveRegion(String region) {
        return region != null && !region.isBlank() ? region : regionResolver.getDefaultRegion();
    }

    private static String asIso(Instant instant) {
        return instant != null ? instant.toString() : null;
    }
}
