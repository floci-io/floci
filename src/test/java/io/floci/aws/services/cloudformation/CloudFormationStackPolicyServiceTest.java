package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.storage.InMemoryStorage;
import io.floci.aws.services.cloudformation.model.ChangeSet;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.model.StackResource;
import io.floci.aws.services.cloudformation.provisioners.CfnResourceDispatcher;
import io.floci.aws.services.cloudformation.provisioners.UpdateCleanupResult;
import io.floci.aws.services.s3.S3Service;
import io.floci.aws.services.s3.model.S3Object;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Covers that a create, an update and SetStackPolicy store a stack policy only alongside a stack state that allows it. */
class CloudFormationStackPolicyServiceTest {

    private static final String ACCOUNT = "000000000000";
    private static final String REGION = "us-east-1";
    private static final String TEMPLATE = "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"}}}";
    private static final String UPDATED_TEMPLATE =
            "{\"Resources\":{\"Queue\":{\"Type\":\"AWS::SQS::Queue\"},\"Topic\":{\"Type\":\"AWS::SNS::Topic\"}}}";
    private static final String POLICY =
            "{\"Statement\":[{\"Effect\":\"Deny\",\"Action\":\"Update:*\",\"Principal\":\"*\",\"Resource\":\"*\"}]}";

    private CfnResourceDispatcher provisioner;
    private S3Service templateStore;
    private RecordingStackStore stackStore;
    private CloudFormationService service;

    @BeforeEach
    void setUp() {
        provisioner = mock(CfnResourceDispatcher.class);
        when(provisioner.provision(anyString(), anyString(), any(), any(), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any())).thenAnswer(invocation -> {
                    StackResource resource = new StackResource();
                    resource.setLogicalId(invocation.getArgument(0));
                    resource.setResourceType(invocation.getArgument(1));
                    resource.setPhysicalId(invocation.getArgument(0) + "-id");
                    resource.setStatus("CREATE_COMPLETE");
                    return resource;
                });
        templateStore = mock(S3Service.class);
        stackStore = new RecordingStackStore();
        service = CloudFormationServiceFixture.create(provisioner, templateStore, Clock.systemUTC(),
                new InMemoryStorageFactory(ACCOUNT).withStore("cloudformation-stacks.json", stackStore));
    }

    @AfterEach
    void stopService() {
        service.stop();
    }

    @Test
    void createChangeSet_storesItsStackPolicyBeforeCreateInProgressIsPersisted() throws Exception {
        ChangeSet changeSet = service.createChangeSet("policy-create", "create", "CREATE", TEMPLATE, null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        changeSet.setStackPolicyBody(POLICY);

        service.executeChangeSet("policy-create", "create", REGION, ACCOUNT).get(30, TimeUnit.SECONDS);

        List<PersistedStack> inProgress = stackStore.puts.stream()
                .filter(put -> "CREATE_IN_PROGRESS".equals(put.status()))
                .toList();
        assertFalse(inProgress.isEmpty());
        assertTrue(inProgress.stream().allMatch(put -> POLICY.equals(put.stackPolicyBody())), inProgress::toString);
        assertEquals(POLICY, service.describeStacks("policy-create", REGION, ACCOUNT).getFirst().getStackPolicyBody());
    }

    @Test
    void updateChangeSet_storesItsStackPolicyBeforeUpdateCompleteIsPersisted() throws Exception {
        when(provisioner.completeUpdate(any())).thenReturn(UpdateCleanupResult.notApplicable());
        createStack("policy-update");
        ChangeSet changeSet = service.createChangeSet("policy-update", "update", "UPDATE", UPDATED_TEMPLATE, null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        changeSet.setStackPolicyBody(POLICY);

        service.executeChangeSet("policy-update", "update", REGION, ACCOUNT).get(30, TimeUnit.SECONDS);

        List<PersistedStack> completed = stackStore.puts.stream()
                .filter(put -> "UPDATE_COMPLETE".equals(put.status()))
                .toList();
        assertFalse(completed.isEmpty());
        assertTrue(completed.stream().allMatch(put -> POLICY.equals(put.stackPolicyBody())), completed::toString);
        assertEquals(POLICY, service.describeStacks("policy-update", REGION, ACCOUNT).getFirst().getStackPolicyBody());
    }

    @Test
    void createStackChangeSetCarriesItsPolicyWhenItIsCreated() {
        ChangeSet changeSet = service.createChangeSetForCreateStack("policy-initial", "initial-create", TEMPLATE,
                null, Map.of(), List.of(), Map.of(), REGION, false, POLICY);

        assertEquals(POLICY, changeSet.getStackPolicyBody());
        assertEquals(POLICY,
                service.describeChangeSet("policy-initial", "initial-create", REGION).getStackPolicyBody());
    }

    @Test
    void updateStackChangeSetWithOnlyANewPolicyIsExecutable() throws Exception {
        createStack("policy-only");

        ChangeSet newPolicy = service.createChangeSetForUpdateStack("policy-only", "new-policy", TEMPLATE, null,
                Map.of(), List.of(), Map.of(), REGION, POLICY);

        assertEquals("CREATE_COMPLETE", newPolicy.getStatus());
        assertEquals("AVAILABLE", newPolicy.getExecutionStatus());
        assertNull(newPolicy.getStatusReason());
        assertEquals(POLICY, newPolicy.getStackPolicyBody());

        service.setStackPolicy("policy-only", POLICY, null, REGION);
        ChangeSet samePolicy = service.createChangeSetForUpdateStack("policy-only", "same-policy", TEMPLATE, null,
                Map.of(), List.of(), Map.of(), REGION, POLICY);

        assertEquals("FAILED", samePolicy.getStatus());
        assertEquals(CloudFormationService.NO_CHANGES_REASON, samePolicy.getStatusReason());
    }

    @Test
    void setStackPolicy_refusesAStackAnUpdateClaimedWhileItsPolicyWasRead() throws Exception {
        createStack("policy-race");
        Stack stack = service.describeStacks("policy-race", REGION, ACCOUNT).getFirst();
        when(templateStore.getObject("policies", "policy.json")).thenAnswer(invocation -> {
            stack.setStatus("UPDATE_IN_PROGRESS");
            return new S3Object("policies", "policy.json", POLICY.getBytes(StandardCharsets.UTF_8),
                    "application/json");
        });

        AwsException error = assertThrows(AwsException.class, () -> service.setStackPolicy(
                "policy-race", null, "http://localhost/policies/policy.json", REGION));

        assertEquals("ValidationError", error.getErrorCode());
        assertEquals("SetStackPolicy cannot be called when stack is in the UPDATE_IN_PROGRESS state",
                error.getMessage());
        assertNull(stack.getStackPolicyBody());
    }

    private void createStack(String stackName) throws Exception {
        service.createChangeSet(stackName, "create", "CREATE", TEMPLATE, null,
                Map.of(), List.of(), Map.of(), REGION, ACCOUNT);
        service.executeChangeSet(stackName, "create", REGION, ACCOUNT).get(30, TimeUnit.SECONDS);
        assertEquals("CREATE_COMPLETE", service.describeStacks(stackName, REGION, ACCOUNT).getFirst().getStatus());
    }

    private record PersistedStack(String status, String stackPolicyBody) {}

    private static final class RecordingStackStore extends InMemoryStorage<String, Stack> {
        private final List<PersistedStack> puts = new CopyOnWriteArrayList<>();

        @Override
        public void put(String key, Stack value) {
            puts.add(new PersistedStack(value.getStatus(), value.getStackPolicyBody()));
            super.put(key, value);
        }
    }
}
