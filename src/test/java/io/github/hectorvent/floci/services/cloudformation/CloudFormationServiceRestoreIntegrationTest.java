package io.github.hectorvent.floci.services.cloudformation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;

@QuarkusTest
class CloudFormationServiceRestoreIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final int ACTIVE_OPERATIONS = 16;
    private static final int QUEUED_OPERATIONS = 128;
    private static final String TEMPLATE = """
            {"Resources":{"Resource":{"Type":"AWS::Test::Resource","Properties":{}}}}
            """;

    @Inject
    CloudFormationService service;

    @InjectMock
    CloudFormationResourceProvisioner provisioner;

    @Test
    void rejectedExecutionRestoresStackAndChangeSetState() throws Exception {
        CountDownLatch activeOperations = new CountDownLatch(ACTIVE_OPERATIONS);
        CountDownLatch releaseOperations = new CountDownLatch(1);
        doAnswer(invocation -> {
            activeOperations.countDown();
            assertTrue(releaseOperations.await(10, TimeUnit.SECONDS));
            StackResource resource = new StackResource();
            resource.setLogicalId(invocation.getArgument(0));
            resource.setResourceType(invocation.getArgument(1));
            resource.setPhysicalId("physical-resource");
            resource.setStatus("CREATE_COMPLETE");
            return resource;
        }).when(provisioner).provision(
                anyString(), anyString(), any(JsonNode.class), any(CloudFormationTemplateEngine.class),
                eq(REGION), anyString(), anyString(), nullable(String.class), anyMap());

        List<Future<?>> runningOperations = new ArrayList<>();
        String targetStack = uniqueStackName("restore-target");
        List<String> fillerStacks = new ArrayList<>();
        try {
            for (int i = 0; i < ACTIVE_OPERATIONS + QUEUED_OPERATIONS + 1; i++) {
                String stackName = i == ACTIVE_OPERATIONS + QUEUED_OPERATIONS
                        ? targetStack : uniqueStackName("restore-filler");
                if (!stackName.equals(targetStack)) {
                    fillerStacks.add(stackName);
                }
                service.createChangeSet(stackName, "initial", "CREATE", TEMPLATE, null,
                        Map.of(), List.of(), Map.of(), REGION);
            }

            for (String fillerStack : fillerStacks.subList(0, ACTIVE_OPERATIONS)) {
                runningOperations.add(service.executeChangeSet(fillerStack, "initial", REGION));
            }

            assertTrue(activeOperations.await(10, TimeUnit.SECONDS),
                    "the executor did not reach its active-operation bound");

            for (String fillerStack : fillerStacks.subList(ACTIVE_OPERATIONS, fillerStacks.size())) {
                runningOperations.add(service.executeChangeSet(fillerStack, "initial", REGION));
            }

            AwsException rejected = assertThrows(AwsException.class,
                    () -> service.executeChangeSet(targetStack, "initial", REGION));
            assertEquals("LimitExceededException", rejected.getErrorCode());

            Stack restored = service.describeStacks(targetStack, REGION).getFirst();
            assertEquals("REVIEW_IN_PROGRESS", restored.getStatus());
            ChangeSet restoredChangeSet = service.describeChangeSet(targetStack, "initial", REGION);
            assertEquals("CREATE_COMPLETE", restoredChangeSet.getStatus());
            assertEquals("AVAILABLE", restoredChangeSet.getExecutionStatus());
        } finally {
            releaseOperations.countDown();
            for (Future<?> operation : runningOperations) {
                operation.get(10, TimeUnit.SECONDS);
            }
        }

        Future<?> retry = service.executeChangeSet(targetStack, "initial", REGION);
        retry.get(10, TimeUnit.SECONDS);
        assertEquals("CREATE_COMPLETE", service.describeStacks(targetStack, REGION).getFirst().getStatus());
        assertEquals("EXECUTE_COMPLETE",
                service.describeChangeSet(targetStack, "initial", REGION).getExecutionStatus());
    }

    private static String uniqueStackName(String prefix) {
        return prefix + "-" + System.nanoTime();
    }
}
