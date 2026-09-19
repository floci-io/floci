package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.withSettings;

/**
 * Fills the bounded operation executor with change set executions that block inside resource
 * provisioning, then checks that the execution it rejects leaves its stack and change set as
 * they were.
 *
 * <p>The provisioner bean is swapped for a mock whose default answer blocks {@code provision}
 * for this test's stacks and forwards everything else to the real provisioner. The answer
 * recognises those calls by method name and stack name rather than by stubbing one overload,
 * because the service picks whichever {@code provision} overload the current main branch has,
 * and a stubbing tied to an older overload silently returns null once main adds a new one.
 */
@QuarkusTest
class CloudFormationServiceRestoreIntegrationTest {

    private static final String REGION = "us-east-1";
    private static final String STACK_PREFIX = "restore-it-";
    private static final int ACTIVE_OPERATIONS = 16;
    private static final int QUEUED_OPERATIONS = 128;
    private static final long TIMEOUT_SECONDS = 30;
    private static final String TEMPLATE = """
            {"Resources":{"Resource":{"Type":"AWS::Test::Resource","Properties":{}}}}
            """;

    @Inject
    CloudFormationService service;

    @Inject
    CloudFormationResourceProvisioner provisioner;

    private final CountDownLatch activeOperations = new CountDownLatch(ACTIVE_OPERATIONS);
    private final CountDownLatch releaseOperations = new CountDownLatch(1);
    private CloudFormationResourceProvisioner blockingProvisioner;

    @BeforeEach
    void blockProvisioningForTestStacks() {
        Answer<Object> realProvisioner = AdditionalAnswers.delegatesTo(ClientProxy.unwrap(provisioner));
        blockingProvisioner = Mockito.mock(CloudFormationResourceProvisioner.class,
                withSettings().defaultAnswer(invocation -> isTestStackProvision(invocation)
                        ? provisionAfterRelease(invocation)
                        : realProvisioner.answer(invocation)));
        QuarkusMock.installMockForType(blockingProvisioner, CloudFormationResourceProvisioner.class);
    }

    @Test
    void rejectedExecutionRestoresStackAndChangeSetState() throws Exception {
        String targetStack = uniqueStackName("target");
        List<String> fillerStacks = new ArrayList<>();
        for (int i = 0; i < ACTIVE_OPERATIONS + QUEUED_OPERATIONS; i++) {
            fillerStacks.add(uniqueStackName("filler"));
        }
        for (String fillerStack : fillerStacks) {
            createChangeSet(fillerStack);
        }
        createChangeSet(targetStack);

        List<Future<?>> runningOperations = new ArrayList<>();
        try {
            for (String fillerStack : fillerStacks.subList(0, ACTIVE_OPERATIONS)) {
                runningOperations.add(service.executeChangeSet(fillerStack, "initial", REGION));
            }

            assertTrue(activeOperations.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    this::describeProvisionerActivity);

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
                operation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
        }

        Future<?> retry = service.executeChangeSet(targetStack, "initial", REGION);
        retry.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("CREATE_COMPLETE", service.describeStacks(targetStack, REGION).getFirst().getStatus());
        assertEquals("EXECUTE_COMPLETE",
                service.describeChangeSet(targetStack, "initial", REGION).getExecutionStatus());
    }

    private void createChangeSet(String stackName) {
        service.createChangeSet(stackName, "initial", "CREATE", TEMPLATE, null,
                Map.of(), List.of(), Map.of(), REGION);
    }

    /** Every {@code provision} overload starts with the logical id and type and carries the stack name. */
    private static boolean isTestStackProvision(InvocationOnMock invocation) {
        return "provision".equals(invocation.getMethod().getName())
                && Arrays.stream(invocation.getArguments())
                        .anyMatch(argument -> argument instanceof String name && name.startsWith(STACK_PREFIX));
    }

    private StackResource provisionAfterRelease(InvocationOnMock invocation) throws InterruptedException {
        activeOperations.countDown();
        assertTrue(releaseOperations.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "blocked operations were not released");
        StackResource resource = new StackResource();
        resource.setLogicalId(invocation.getArgument(0));
        resource.setResourceType(invocation.getArgument(1));
        resource.setPhysicalId("physical-resource");
        resource.setStatus("CREATE_COMPLETE");
        return resource;
    }

    private String describeProvisionerActivity() {
        Map<String, Long> callsByMethod = Mockito.mockingDetails(blockingProvisioner).getInvocations().stream()
                .collect(Collectors.groupingBy(invocation -> invocation.getMethod().getName(),
                        TreeMap::new, Collectors.counting()));
        return (ACTIVE_OPERATIONS - activeOperations.getCount()) + " of " + ACTIVE_OPERATIONS
                + " blocked operations reached the provisioner; provisioner calls by method: " + callsByMethod;
    }

    private static String uniqueStackName(String kind) {
        return STACK_PREFIX + kind + "-" + System.nanoTime();
    }
}
