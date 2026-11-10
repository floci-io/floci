package io.floci.aws.services.cloudformation;

import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.services.cloudformation.model.Stack;
import io.floci.aws.services.cloudformation.model.StackSetOperation;
import io.floci.aws.services.organizations.OrganizationsService;
import io.floci.aws.services.organizations.model.Organization;
import io.floci.aws.services.organizations.model.OrganizationAccount;
import io.floci.aws.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StackSetAutoDeploymentOperationTest {
    private static final String MANAGEMENT = "111111111111";
    private static final String ACCOUNT = "222222222222";
    private static final String REGION = "us-east-1";
    private static final String TARGET = "ou-abcd-abcdefgh";
    private static final String SET = "auto-operations";

    private CloudFormationService cloudFormation;
    private OrganizationsService organizations;
    private StackSetService service;
    private Stack stack;

    @BeforeEach
    void setUp() {
        cloudFormation = mock(CloudFormationService.class);
        organizations = mock(OrganizationsService.class);
        Organization organization = new Organization();
        organization.setMasterAccountId(MANAGEMENT);
        organization.setFeatureSet("ALL");
        when(organizations.describeOrganization(MANAGEMENT)).thenReturn(organization);
        when(organizations.listAccountsForParent(MANAGEMENT, TARGET)).thenReturn(List.of());
        when(organizations.listOrganizationalUnitsForParent(MANAGEMENT, TARGET)).thenReturn(List.of());
        OrganizationAccount account = new OrganizationAccount();
        account.setId(ACCOUNT);
        account.setStatus("ACTIVE");
        doReturn(account).when(organizations).describeAccount(MANAGEMENT, ACCOUNT);
        when(organizations.organizationPath(MANAGEMENT, ACCOUNT)).thenReturn("r-abcd/" + TARGET + "/" + ACCOUNT);
        when(cloudFormation.executeChangeSet(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        stack = new Stack();
        stack.setStackId("stack-id");
        stack.setStatus("CREATE_COMPLETE");
        when(cloudFormation.describeStacks(anyString(), anyString(), anyString())).thenReturn(List.of(stack));
        service = new StackSetService(cloudFormation, new InMemoryStorageFactory(MANAGEMENT), organizations,
                new RegionResolver(REGION, MANAGEMENT));
        service.activateOrganizationsAccess();
        service.createStackSet(SET, "{\"Resources\":{}}", null, null, null, null,
                "SERVICE_MANAGED", true, false, false);
        service.createStackInstances(SET, List.of(), List.of(REGION), List.of(TARGET), null);
    }

    @AfterEach
    void stopAccountPlacementExecutor() {
        service.stopAccountPlacementExecutor();
    }

    @Test
    void failedAutomaticCreateRecordsFailedOperation() {
        stack.setStatus("ROLLBACK_COMPLETE");
        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertAutomaticOperation("CREATE", "FAILED", 2));
        assertEquals("FAILED", service.describeStackInstance(SET, ACCOUNT, REGION).getDetailedStatus());
    }

    @Test
    void failedAutomaticDeleteRecordsFailedOperationAndKeepsInstance() {
        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertAutomaticOperation("CREATE", "SUCCEEDED", 2));
        when(organizations.describeAccount(MANAGEMENT, ACCOUNT))
                .thenThrow(new AwsException("AccountNotFoundException", "Account left the organization", 404));
        when(cloudFormation.deleteStack(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("Delete failed")));

        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertAutomaticOperation("DELETE", "FAILED", 3));
        assertEquals("INOPERABLE", service.describeStackInstance(SET, ACCOUNT, REGION).getStatus());
    }

    @Test
    void automaticCreateExceptionCompletesOperationAsFailed() {
        when(cloudFormation.createChangeSet(anyString(), anyString(), anyString(), anyString(),
                isNull(), any(), any(), any(), anyString(), anyString()))
                .thenThrow(new AwsException("ValidationError", "Invalid deployment", 400));

        assertDoesNotThrow(() -> service.accountPlacementChanged(MANAGEMENT, ACCOUNT));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertAutomaticOperation("CREATE", "FAILED", 2));
    }

    @Test
    void stackExecutionCanNotifyAccountPlacementWhileTheCallerWaits() {
        OrganizationAccount account = organizations.describeAccount(MANAGEMENT, ACCOUNT);
        when(organizations.listAccountsForParent(MANAGEMENT, TARGET)).thenReturn(List.of(account));
        when(cloudFormation.executeChangeSet(anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(ignored -> CompletableFuture.runAsync(() ->
                        service.accountPlacementChanged(MANAGEMENT, ACCOUNT)));

        assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                service.createStackInstances(SET, List.of(), List.of(REGION), List.of(TARGET), null));
    }

    @Test
    void aFailedDeploymentDoesNotPreventAnotherStackSetFromDeploying() {
        String healthy = "healthy-auto";
        service.createStackSet(healthy, "{\"Resources\":{}}", null, null, null, null,
                "SERVICE_MANAGED", true, false, false);
        service.createStackInstances(healthy, List.of(), List.of(REGION), List.of(TARGET), null);
        when(cloudFormation.createChangeSet(argThat(name -> name != null && name.startsWith("StackSet-" + SET + "-")),
                anyString(), anyString(), anyString(), isNull(), any(), any(), any(), anyString(), anyString()))
                .thenThrow(new AwsException("ValidationError", "Invalid deployment", 400));

        assertDoesNotThrow(() -> service.accountPlacementChanged(MANAGEMENT, ACCOUNT));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertAutomaticOperation("CREATE", "FAILED", 2);
            assertEquals(1, service.listStackInstances(healthy, ACCOUNT, REGION).size());
            assertEquals("SUCCEEDED", service.describeStackInstance(healthy, ACCOUNT, REGION).getDetailedStatus());
        });
    }

    @Test
    void accountWorkerKeepsTheRuntimeClassLoaderWithoutInheritingRequestThreadLocals() throws Exception {
        OrganizationAccount account = organizations.describeAccount(MANAGEMENT, ACCOUNT);
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        ClassLoader runtime = new ClassLoader(original) {};
        InheritableThreadLocal<String> requestValue = new InheritableThreadLocal<>();
        CompletableFuture<WorkerContext> observed = new CompletableFuture<>();
        when(organizations.describeAccount(MANAGEMENT, ACCOUNT)).thenAnswer(ignored -> {
            observed.complete(new WorkerContext(Thread.currentThread().getContextClassLoader(), requestValue.get()));
            return account;
        });
        requestValue.set("request");
        Thread.currentThread().setContextClassLoader(runtime);
        try {
            service.accountPlacementChanged(MANAGEMENT, ACCOUNT);
            WorkerContext context = observed.get(5, TimeUnit.SECONDS);
            assertSame(runtime, context.classLoader());
            assertNull(context.requestValue());
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertAutomaticOperation("CREATE", "SUCCEEDED", 2));
        } finally {
            Thread.currentThread().setContextClassLoader(original);
            requestValue.remove();
        }
    }

    private record WorkerContext(ClassLoader classLoader, String requestValue) {}

    @Test
    void aRemovedOrganizationDoesNotPreventAutomaticDeletionOfItsAccountInstances() {
        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertAutomaticOperation("CREATE", "SUCCEEDED", 2));
        when(organizations.describeAccount(MANAGEMENT, ACCOUNT))
                .thenThrow(new AwsException("AWSOrganizationsNotInUseException", "The organization was deleted", 400));
        when(cloudFormation.deleteStack(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));

        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertAutomaticOperation("DELETE", "SUCCEEDED", 3);
            assertEquals(0, service.listStackInstances(SET, ACCOUNT, REGION).size());
        });
    }

    @Test
    void aRejectedAutomaticDeleteMarksTheInstanceFailed() {
        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertAutomaticOperation("CREATE", "SUCCEEDED", 2));
        when(organizations.describeAccount(MANAGEMENT, ACCOUNT))
                .thenThrow(new AwsException("AccountNotFoundException", "Account left the organization", 404));
        when(cloudFormation.deleteStack(anyString(), anyString(), anyString()))
                .thenThrow(new AwsException("ValidationError", "Termination protection is enabled", 400));

        assertDoesNotThrow(() -> service.accountPlacementChanged(MANAGEMENT, ACCOUNT));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertAutomaticOperation("DELETE", "FAILED", 3);
            assertEquals("INOPERABLE", service.describeStackInstance(SET, ACCOUNT, REGION).getStatus());
            assertEquals("Termination protection is enabled",
                    service.describeStackInstance(SET, ACCOUNT, REGION).getStatusReason());
        });
    }

    @Test
    void aRetainedStackDoesNotPreventAnAccountReturningToItsTarget() {
        service.describeStackSet(SET).setRetainStacksOnAccountRemoval(true);
        OrganizationAccount account = organizations.describeAccount(MANAGEMENT, ACCOUNT);
        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertAutomaticOperation("CREATE", "SUCCEEDED", 2));
        String retainedName = service.describeStackInstance(SET, ACCOUNT, REGION).getStackName();
        when(organizations.describeAccount(MANAGEMENT, ACCOUNT))
                .thenThrow(new AwsException("AccountNotFoundException", "Account left the organization", 404));
        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertAutomaticOperation("DELETE", "SUCCEEDED", 3));
        doReturn(account).when(organizations).describeAccount(MANAGEMENT, ACCOUNT);
        when(cloudFormation.stackStatus(retainedName, REGION, ACCOUNT)).thenReturn("CREATE_COMPLETE");
        when(cloudFormation.createChangeSet(eq(retainedName), anyString(), anyString(), anyString(),
                isNull(), any(), any(), any(), anyString(), anyString()))
                .thenThrow(new AwsException("AlreadyExistsException", "The retained stack exists", 400));

        service.accountPlacementChanged(MANAGEMENT, ACCOUNT);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            assertAutomaticOperation("CREATE", "SUCCEEDED", 4);
            assertNotEquals(retainedName, service.describeStackInstance(SET, ACCOUNT, REGION).getStackName());
        });
    }

    @Test
    void aRejectedExplicitDeleteRecordsFailureAndContinuesOtherInstances() {
        String protectedAccount = "333333333333";
        String otherAccount = "444444444444";
        OrganizationAccount first = organizations.describeAccount(MANAGEMENT, ACCOUNT);
        OrganizationAccount protectedMember = new OrganizationAccount();
        protectedMember.setId(protectedAccount);
        protectedMember.setStatus("ACTIVE");
        OrganizationAccount other = new OrganizationAccount();
        other.setId(otherAccount);
        other.setStatus("ACTIVE");
        when(organizations.listAccountsForParent(MANAGEMENT, TARGET))
                .thenReturn(List.of(first, protectedMember, other));
        service.createStackInstances(SET, List.of(), List.of(REGION), List.of(TARGET), null);
        service.describeStackSet(SET).setRetainStacksOnAccountRemoval(true);
        when(cloudFormation.deleteStack(anyString(), eq(REGION), anyString()))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(cloudFormation.deleteStack(anyString(), eq(REGION), eq(protectedAccount)))
                .thenThrow(new AwsException("ValidationError", "Termination protection is enabled", 400));

        StackSetOperation operation = assertDoesNotThrow(() ->
                service.deleteStackInstances(SET, List.of(), List.of(REGION), List.of(TARGET), false));

        assertEquals("FAILED", operation.getStatus());
        assertEquals("FAILED",
                service.describeStackSetOperation(SET, operation.getOperationId()).getStatus());
        assertNotNull(operation.getCreationTimestamp());
        assertNotNull(operation.getEndTimestamp());
        assertEquals(1, service.listStackInstances(SET, null, null).size());
        assertEquals("INOPERABLE", service.describeStackInstance(SET, protectedAccount, REGION).getStatus());
        assertEquals("FAILED", service.describeStackInstance(SET, protectedAccount, REGION).getDetailedStatus());
        assertEquals("Termination protection is enabled",
                service.describeStackInstance(SET, protectedAccount, REGION).getStatusReason());
        assertEquals(1, service.listStackSetAutoDeploymentTargets(SET).size());
        verify(cloudFormation).deleteStack(anyString(), eq(REGION), eq(ACCOUNT));
        verify(cloudFormation).deleteStack(anyString(), eq(REGION), eq(otherAccount));

        doReturn(CompletableFuture.completedFuture(null)).when(cloudFormation)
                .deleteStack(anyString(), eq(REGION), eq(protectedAccount));
        StackSetOperation retry = service.deleteStackInstances(
                SET, List.of(), List.of(REGION), List.of(TARGET), false);

        assertEquals("SUCCEEDED", retry.getStatus());
        assertEquals(0, service.listStackInstances(SET, null, null).size());
        assertEquals(0, service.listStackSetAutoDeploymentTargets(SET).size());
    }

    @Test
    void anExplicitRetainedDeleteDoesNotUseTheAutomaticRemovalFlag() {
        OrganizationAccount account = organizations.describeAccount(MANAGEMENT, ACCOUNT);
        when(organizations.listAccountsForParent(MANAGEMENT, TARGET)).thenReturn(List.of(account));
        service.createStackInstances(SET, List.of(), List.of(REGION), List.of(TARGET), null);

        StackSetOperation operation = service.deleteStackInstances(
                SET, List.of(), List.of(REGION), List.of(TARGET), true);

        assertEquals("SUCCEEDED", operation.getStatus());
        assertEquals(0, service.listStackInstances(SET, null, null).size());
        assertEquals(0, service.listStackSetAutoDeploymentTargets(SET).size());
        verify(cloudFormation, never()).deleteStack(anyString(), anyString(), anyString());
    }

    @Test
    void aRejectedSelfManagedDeleteRecordsTheFailedInstanceAndOperation() {
        String name = "explicit-self-managed";
        service.createStackSet(name, "{\"Resources\":{}}", null, null, null, null);
        service.createStackInstances(name, List.of(ACCOUNT), List.of(REGION));
        when(cloudFormation.deleteStack(anyString(), anyString(), anyString()))
                .thenThrow(new AwsException("ValidationError", "Termination protection is enabled", 400));

        StackSetOperation operation = assertDoesNotThrow(() ->
                service.deleteStackInstances(name, List.of(ACCOUNT), List.of(REGION), false));

        assertEquals("FAILED", operation.getStatus());
        assertEquals("FAILED",
                service.describeStackSetOperation(name, operation.getOperationId()).getStatus());
        assertEquals("INOPERABLE", service.describeStackInstance(name, ACCOUNT, REGION).getStatus());
        assertEquals("FAILED", service.describeStackInstance(name, ACCOUNT, REGION).getDetailedStatus());
        assertEquals("Termination protection is enabled",
                service.describeStackInstance(name, ACCOUNT, REGION).getStatusReason());
    }

    private void assertAutomaticOperation(String action, String status, int count) {
        List<StackSetOperation> operations = service.listStackSetOperations(SET);
        assertEquals(count, operations.size());
        StackSetOperation operation = operations.getFirst();
        assertEquals(action, operation.getAction());
        assertEquals(status, operation.getStatus());
        assertNotNull(operation.getCreationTimestamp());
        assertNotNull(operation.getEndTimestamp());
        assertEquals(operation.getOperationId(),
                service.describeStackSetOperation(SET, operation.getOperationId()).getOperationId());
    }

}
