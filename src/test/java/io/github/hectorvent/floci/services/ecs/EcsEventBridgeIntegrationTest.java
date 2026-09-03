package io.github.hectorvent.floci.services.ecs;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.ecs.container.EcsContainerManager;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;
import io.github.hectorvent.floci.services.ecs.model.EcsTask;
import io.github.hectorvent.floci.services.ecs.model.LaunchType;
import io.github.hectorvent.floci.services.ecs.model.TaskDefinition;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeInvoker;
import io.github.hectorvent.floci.services.eventbridge.EventBridgeService;
import io.github.hectorvent.floci.services.eventbridge.model.RuleState;
import io.github.hectorvent.floci.services.eventbridge.model.Target;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EcsEventBridgeIntegrationTest {

    private static final String REGION = "us-east-1";
    private final ObjectMapper objectMapper = new ObjectMapper();

    private EventBridgeService eventBridgeService;
    private EventBridgeInvoker invoker;
    private EcsService ecsService;
    private List<String> deliveredEvents;

    @BeforeEach
    void setUp() {
        deliveredEvents = new CopyOnWriteArrayList<>();
        invoker = mock(EventBridgeInvoker.class);
        doAnswer(invocation -> {
            String eventJson = invocation.getArgument(1);
            deliveredEvents.add(eventJson);
            return null;
        }).when(invoker).invokeTarget(any(), anyString(), anyString());

        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        InMemoryStorageFactory storageFactory = new InMemoryStorageFactory();
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().ecs().mock()).thenReturn(true);
        when(config.effectiveBaseUrl()).thenReturn("http://localhost:4566");

        eventBridgeService = new EventBridgeService(
                storageFactory,
                config,
                regionResolver,
                objectMapper,
                null,
                invoker,
                null,
                null
        );

        EcsEventPublisher eventPublisher = new EcsEventPublisher(eventBridgeService, regionResolver, objectMapper);

        ecsService = new EcsService(
                regionResolver,
                mock(EcsContainerManager.class),
                config,
                mock(EcsLoadBalancerRegistrar.class),
                storageFactory,
                eventPublisher
        );
        ecsService.initializeStorage();
    }

    private static TaskDefinition registerTaskDef(EcsService service, String family, String image) {
        ContainerDefinition cd = new ContainerDefinition();
        cd.setName("app");
        cd.setImage(image);
        return service.registerTaskDefinition(family, List.of(cd), null, null, null,
                null, null, List.of(), REGION);
    }

    @Test
    void deploymentStateChangeDeliveredToEventBridgeOnServiceReconcile() {
        eventBridgeService.putRule(
                "ecs-deploy-rule",
                null,
                "{\"source\":[\"aws.ecs\"],\"detail-type\":[\"ECS Deployment State Change\"]}",
                null,
                RuleState.ENABLED,
                "ECS deployment rule",
                null,
                null,
                REGION
        );

        Target target = new Target();
        target.setId("deploy-target-1");
        target.setArn("arn:aws:sqs:us-east-1:000000000000:deploy-queue");
        eventBridgeService.putTargets("ecs-deploy-rule", null, List.of(target), REGION);

        ecsService.createCluster("deploy-cluster", REGION);
        registerTaskDef(ecsService, "deploy-fam", "app:1");

        ecsService.createService("deploy-cluster", "deploy-svc", "deploy-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        // Tick 1: task launched, desiredCount is being satisfied
        ecsService.reconcileServices();
        // Tick 2: converged to steady state
        ecsService.reconcileServices();

        List<JsonNode> events = deliveredEvents.stream().map(json -> {
            try {
                return objectMapper.readTree(json);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();

        assertTrue(events.stream().anyMatch(evt ->
                "aws.ecs".equals(evt.path("source").asText())
                && "ECS Deployment State Change".equals(evt.path("detail-type").asText())
                && "SERVICE_DEPLOYMENT_COMPLETED".equals(evt.path("detail").path("eventName").asText())
                && "INFO".equals(evt.path("detail").path("eventType").asText())
                && evt.path("detail").path("deploymentId").asText().startsWith("ecs-svc/")
        ), "Expected SERVICE_DEPLOYMENT_COMPLETED event in delivered events");
    }

    @Test
    void taskStateChangeDeliveredToEventBridgeOnTaskStop() {
        eventBridgeService.putRule(
                "ecs-task-rule",
                null,
                "{\"source\":[\"aws.ecs\"],\"detail-type\":[\"ECS Task State Change\"]}",
                null,
                RuleState.ENABLED,
                "ECS task rule",
                null,
                null,
                REGION
        );

        Target target = new Target();
        target.setId("task-target-1");
        target.setArn("arn:aws:sqs:us-east-1:000000000000:task-queue");
        eventBridgeService.putTargets("ecs-task-rule", null, List.of(target), REGION);

        ecsService.createCluster("task-cluster", REGION);
        registerTaskDef(ecsService, "task-fam", "app:1");

        ecsService.createService("task-cluster", "task-svc", "task-fam", 1,
                LaunchType.FARGATE, List.of(), null, REGION);

        ecsService.reconcileServices();

        List<EcsTask> runningTasks = ecsService.describeTasks(
                "task-cluster",
                ecsService.listTasks("task-cluster", null, null, null, REGION),
                REGION
        );
        assertEquals(1, runningTasks.size());
        String taskArn = runningTasks.getFirst().getTaskArn();

        ecsService.stopTask("task-cluster", taskArn, "test-stop", REGION);

        List<JsonNode> events = deliveredEvents.stream().map(json -> {
            try {
                return objectMapper.readTree(json);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();

        assertTrue(events.stream().anyMatch(evt ->
                "aws.ecs".equals(evt.path("source").asText())
                && "ECS Task State Change".equals(evt.path("detail-type").asText())
                && "STOPPED".equals(evt.path("detail").path("lastStatus").asText())
                && taskArn.equals(evt.path("detail").path("taskArn").asText())
        ), "Expected STOPPED ECS Task State Change event with matching taskArn in delivered events");
    }

    private static final class InMemoryStorageFactory extends StorageFactory {
        private final Map<String, StorageBackend<String, ?>> stores = new HashMap<>();

        private InMemoryStorageFactory() {
            super(null, null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <V> AccountAwareStorageBackend<V> create(String serviceName,
                                                        String fileName,
                                                        TypeReference<Map<String, V>> typeReference) {
            return (AccountAwareStorageBackend<V>) stores.computeIfAbsent(fileName,
                    ignored -> AccountAwareStorageBackend.inMemory("000000000000"));
        }
    }
}
