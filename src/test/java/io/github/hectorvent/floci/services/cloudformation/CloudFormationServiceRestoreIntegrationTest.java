package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.services.cloudformation.model.ChangeSet;
import io.github.hectorvent.floci.services.cloudformation.model.Stack;
import io.github.hectorvent.floci.services.cloudformation.model.StackResource;
import io.github.hectorvent.floci.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.xml.XmlPath;
import io.restassured.path.xml.element.Node;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.github.hectorvent.floci.testing.CfnRequests.cfn;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.withSettings;

/**
 * Fills the bounded operation executor with change set executions that block inside resource
 * provisioning, then checks that the execution or stack delete it rejects leaves the stack (and
 * change set) as they were, and can be retried once there is room.
 *
 * <p>The dispatcher bean is swapped for a mock whose default answer blocks {@code provision}
 * for this test's stacks and forwards everything else to the real dispatcher. The answer
 * recognises those calls by method name and stack name rather than by stubbing one overload, so
 * it does not depend on which {@code provision} overload the service calls.
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
    CfnResourceDispatcher provisioner;

    @Inject
    RegionResolver regionResolver;

    private final CountDownLatch activeOperations = new CountDownLatch(ACTIVE_OPERATIONS);
    private final CountDownLatch releaseOperations = new CountDownLatch(1);
    private CfnResourceDispatcher blockingProvisioner;

    @BeforeEach
    void blockProvisioningForTestStacks() {
        Answer<Object> realProvisioner = AdditionalAnswers.delegatesTo(ClientProxy.unwrap(provisioner));
        blockingProvisioner = Mockito.mock(CfnResourceDispatcher.class,
                withSettings().defaultAnswer(invocation -> isTestStackProvision(invocation)
                        ? provisionAfterRelease(invocation)
                        : realProvisioner.answer(invocation)));
        QuarkusMock.installMockForType(blockingProvisioner, CfnResourceDispatcher.class);
    }

    @Test
    void rejectedExecutionRestoresStackAndChangeSetState() throws Exception {
        String targetStack = uniqueStackName("target");
        createChangeSet(targetStack);
        String policy = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"Update:*\",\"Principal\":\"*\",\"Resource\":\"*\"}]}";
        service.describeChangeSet(targetStack, "initial", REGION).setStackPolicyBody(policy);

        List<Future<?>> runningOperations = fillExecutor();
        try {
            AwsException rejected = assertThrows(AwsException.class,
                    () -> service.executeChangeSet(targetStack, "initial", REGION));
            assertEquals("LimitExceededException", rejected.getErrorCode());

            Stack restored = service.describeStacks(targetStack, REGION).getFirst();
            assertEquals("REVIEW_IN_PROGRESS", restored.getStatus());
            assertNull(restored.getStackPolicyBody());
            ChangeSet restoredChangeSet = service.describeChangeSet(targetStack, "initial", REGION);
            assertEquals("CREATE_COMPLETE", restoredChangeSet.getStatus());
            assertEquals("AVAILABLE", restoredChangeSet.getExecutionStatus());
        } finally {
            release(runningOperations);
        }

        Future<?> retry = service.executeChangeSet(targetStack, "initial", REGION);
        retry.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("CREATE_COMPLETE", service.describeStacks(targetStack, REGION).getFirst().getStatus());
        assertEquals(policy, service.describeStacks(targetStack, REGION).getFirst().getStackPolicyBody());
        assertEquals("EXECUTE_COMPLETE",
                service.describeChangeSet(targetStack, "initial", REGION).getExecutionStatus());
    }

    @Test
    void rejectedDeleteLeavesTheStackAsItWasAndCanBeRetried() throws Exception {
        // Outside STACK_PREFIX, so its own create is not held by the blocking provisioner.
        String targetStack = "restore-delete-target-" + System.nanoTime();
        createChangeSet(targetStack);
        service.executeChangeSet(targetStack, "initial", REGION).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Stack stack = service.describeStacks(targetStack, REGION).getFirst();
        assertEquals("CREATE_COMPLETE", stack.getStatus());

        List<Future<?>> runningOperations = fillExecutor();
        try {
            AwsException rejected = assertThrows(AwsException.class,
                    () -> service.deleteStack(targetStack, List.of(), REGION));
            assertEquals("LimitExceededException", rejected.getErrorCode());
            assertEquals("CREATE_COMPLETE", service.describeStacks(targetStack, REGION).getFirst().getStatus());
        } finally {
            release(runningOperations);
        }

        service.deleteStack(targetStack, REGION, regionResolver.getAccountId()).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("DELETE_COMPLETE", stack.getStatus());
    }

    @Test
    void rejectedProtectedCreateLeavesDeletablePlaceholder() throws Exception {
        String targetStack = uniqueStackName("protected");
        List<Future<?>> runningOperations = fillExecutor();
        try {
            Response rejected = cfn("CreateStack", targetStack)
                    .formParam("TemplateBody", TEMPLATE)
                    .formParam("EnableTerminationProtection", "true")
                    .when().post("/");
            assertEquals(400, rejected.statusCode(), rejected.asString());
            assertTrue(rejected.asString().contains("LimitExceededException"), rejected.asString());

            Stack placeholder = service.describeStacks(targetStack, REGION).getFirst();
            assertEquals("REVIEW_IN_PROGRESS", placeholder.getStatus());
            assertFalse(placeholder.isEnableTerminationProtection());
        } finally {
            release(runningOperations);
        }

        service.deleteStack(targetStack, REGION, regionResolver.getAccountId())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void rejectedPolicyOnlyUpdateCanBeExecutedOnceCapacityReturns() throws Exception {
        // Outside STACK_PREFIX, so its own create and update are not held by the blocking provisioner.
        String targetStack = "restore-policy-target-" + System.nanoTime();
        cfn("CreateStack", targetStack).formParam("TemplateBody", TEMPLATE)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(targetStack, "CREATE_COMPLETE");
        String policy = "{\"Statement\":[{\"Effect\":\"Allow\",\"Action\":\"Update:*\",\"Principal\":\"*\",\"Resource\":\"*\"}]}";

        String updateChangeSet;
        List<Future<?>> runningOperations = fillExecutor();
        try {
            Response rejected = cfn("UpdateStack", targetStack)
                    .formParam("UsePreviousTemplate", "true")
                    .formParam("StackPolicyBody", policy)
                    .when().post("/");
            assertEquals(400, rejected.statusCode(), rejected.asString());
            assertTrue(rejected.asString().contains("LimitExceededException"), rejected.asString());

            String listed = cfn("ListChangeSets", targetStack).when().post("/")
                    .then().statusCode(200).extract().asString();
            List<Node> updates = XmlPath.from(listed)
                    .getList("**.findAll { it.name() == 'member' }", Node.class).stream()
                    .filter(summary -> summary.getNode("ChangeSetName").value().startsWith("update-"))
                    .toList();
            assertEquals(1, updates.size(), listed);
            Node restored = updates.getFirst();
            updateChangeSet = restored.getNode("ChangeSetName").value();
            assertEquals("AVAILABLE", restored.getNode("ExecutionStatus").value(), listed);
            assertEquals("CREATE_COMPLETE", restored.getNode("Status").value(), listed);
        } finally {
            release(runningOperations);
        }

        cfn("ExecuteChangeSet", targetStack).formParam("ChangeSetName", updateChangeSet)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStatus(targetStack, "UPDATE_COMPLETE");
        String applied = cfn("GetStackPolicy", targetStack).when().post("/")
                .then().statusCode(200).extract().xmlPath()
                .getString("GetStackPolicyResponse.GetStackPolicyResult.StackPolicyBody");
        assertEquals(policy, applied);

        service.deleteStack(targetStack, REGION, regionResolver.getAccountId())
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Test
    void rejectedCreateCleanupDoesNotUnprotectAnExecutingStack() throws Exception {
        String targetStack = uniqueStackName("executing");
        ChangeSet initial = service.createChangeSetForCreateStack(targetStack, "initial-create",
                TEMPLATE, null, Map.of(), List.of(), Map.of(), REGION, true, null);
        Future<?> execution = service.executeChangeSet(targetStack, "initial-create", REGION);
        try {
            service.clearProtectionAfterRejectedCreate(targetStack, initial, REGION);
            Stack stack = service.describeStacks(targetStack, REGION).getFirst();
            assertEquals("CREATE_IN_PROGRESS", stack.getStatus());
            assertTrue(stack.isEnableTerminationProtection());
        } finally {
            releaseOperations.countDown();
            execution.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            service.updateTerminationProtection(targetStack, false, REGION);
            service.deleteStack(targetStack, REGION, regionResolver.getAccountId())
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /** Starts change set executions that block in provisioning until every thread and queue slot is taken. */
    private List<Future<?>> fillExecutor() throws InterruptedException {
        List<String> fillerStacks = new ArrayList<>();
        for (int i = 0; i < ACTIVE_OPERATIONS + QUEUED_OPERATIONS; i++) {
            fillerStacks.add(uniqueStackName("filler"));
        }
        for (String fillerStack : fillerStacks) {
            createChangeSet(fillerStack);
        }

        List<Future<?>> runningOperations = new ArrayList<>();
        for (String fillerStack : fillerStacks.subList(0, ACTIVE_OPERATIONS)) {
            runningOperations.add(service.executeChangeSet(fillerStack, "initial", REGION));
        }
        assertTrue(activeOperations.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                this::describeProvisionerActivity);
        for (String fillerStack : fillerStacks.subList(ACTIVE_OPERATIONS, fillerStacks.size())) {
            runningOperations.add(service.executeChangeSet(fillerStack, "initial", REGION));
        }
        return runningOperations;
    }

    private void release(List<Future<?>> runningOperations) throws Exception {
        releaseOperations.countDown();
        for (Future<?> operation : runningOperations) {
            operation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
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
        return STACK_PREFIX + kind + "-" + UUID.randomUUID();
    }
}
