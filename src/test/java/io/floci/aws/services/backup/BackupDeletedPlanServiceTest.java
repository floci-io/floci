package io.floci.aws.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.common.ServiceConfigAccess;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.backup.model.BackupPlan;
import io.floci.aws.services.backup.model.BackupSelection;
import io.floci.aws.services.sns.SnsService;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupDeletedPlanServiceTest {
    private static final String ACCOUNT = "741677900013";
    private static final String REGION = "eu-west-2";

    @ParameterizedTest
    @ValueSource(strings = {"persistent", "hybrid", "wal"})
    void deletionSurvivesStorageLifecycleWithoutRestoringActiveAccess(String mode, @TempDir Path path) {
        EmulatorConfig config = config();
        when(config.storage().persistentPath()).thenReturn(path.toString());
        when(config.storage().wal().compactionIntervalMs()).thenReturn(60000L);
        ServiceConfigAccess access = mock(ServiceConfigAccess.class);
        when(access.storageMode("backup")).thenReturn(mode);
        when(access.storageFlushInterval("backup")).thenReturn(60000L);
        StorageFactory storage = new StorageFactory(config, access);
        BackupService service = service(storage, config);
        BackupPlan deleted;
        try {
            BackupPlan plan = service.createBackupPlan("retained", List.of(), null, Map.of("env", "test"), REGION);
            deleted = service.deleteBackupPlan(plan.getBackupPlanId());
            assertNotNull(deleted.getDeletionDate());
        } finally {
            service.shutdown();
            storage.shutdownAll();
        }
        StorageFactory reloadedStorage = new StorageFactory(config, access);
        BackupService reloaded = service(reloadedStorage, config);
        try {
            List<BackupPlan> history = reloaded.listBackupPlans(REGION, true, null, null).items();
            assertEquals(1, history.size());
            BackupPlan restored = history.getFirst();
            assertEquals(deleted.getBackupPlanId(), restored.getBackupPlanId());
            assertEquals(deleted.getBackupPlanArn(), restored.getBackupPlanArn());
            assertEquals(deleted.getVersionId(), restored.getVersionId());
            assertEquals(deleted.getCreationDate(), restored.getCreationDate());
            assertEquals(deleted.getDeletionDate(), restored.getDeletionDate());
            assertTrue(reloaded.listBackupPlans().isEmpty());
            assertTrue(reloaded.listBackupPlans(REGION, false, null, null).items().isEmpty());
            assertTrue(reloaded.listBackupPlans("us-east-1", true, null, null).items().isEmpty());
            assertNotFound(() -> reloaded.getBackupPlan(restored.getBackupPlanId()));
            assertNotFound(() -> reloaded.deleteBackupPlan(restored.getBackupPlanId()));
            assertNotFound(() -> reloaded.tagResource(restored.getBackupPlanArn(), Map.of("late", "tag")));
        } finally {
            reloaded.shutdown();
            reloadedStorage.shutdownAll();
        }
    }

    @Test
    void failedRetentionWriteLeavesThePlanActiveAndRetryable() {
        AccountAwareStorageBackend<BackupPlan> plans = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
        StorageFactory storage = storage(plans, AccountAwareStorageBackend.inMemory(ACCOUNT));
        BackupService service = service(storage, config());
        try {
            BackupPlan plan = service.createBackupPlan("retry", List.of(), null, REGION);
            doThrow(new IllegalStateException("write refused")).when(plans).put(eq(plan.getBackupPlanId()), any());
            assertThrows(IllegalStateException.class, () -> service.deleteBackupPlan(plan.getBackupPlanId()));
            assertSame(plan, service.getBackupPlan(plan.getBackupPlanId()));
            assertNull(plan.getDeletionDate());
            assertEquals(1, service.listBackupPlans().size());
            doCallRealMethod().when(plans).put(eq(plan.getBackupPlanId()), any());
            service.deleteBackupPlan(plan.getBackupPlanId());
            assertEquals(1, service.listBackupPlans(REGION, true, null, null).items().size());
            assertNotFound(() -> service.getBackupPlan(plan.getBackupPlanId()));
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void selectionAndDeletionPublishInPlanLockOrder(boolean deleteFirst) throws Exception {
        ParkingStore<BackupPlan> innerPlans = new ParkingStore<>();
        ParkingStore<BackupSelection> innerSelections = new ParkingStore<>();
        AccountAwareStorageBackend<BackupPlan> plans = new AccountAwareStorageBackend<>(innerPlans, null, ACCOUNT);
        AccountAwareStorageBackend<BackupSelection> selections =
                new AccountAwareStorageBackend<>(innerSelections, null, ACCOUNT);
        BackupService service = service(storage(plans, selections), config());
        try {
            BackupPlan plan = service.createBackupPlan("ordered", List.of(), null, REGION);
            Runnable delete = () -> service.deleteBackupPlan(plan.getBackupPlanId());
            Runnable select = () -> service.createBackupSelection(plan.getBackupPlanId(), "selection", "role",
                    List.of("resource"), List.of(), null);
            ParkingStore<?> parkedStore = deleteFirst ? innerPlans : innerSelections;
            Runnable first = deleteFirst ? delete : select;
            Runnable competing = deleteFirst ? select : delete;
            String expectedError = deleteFirst ? "ResourceNotFoundException" : "InvalidRequestException";
            parkedStore.parkNextWrite();
            assertOperationWaitsForParkedStore(parkedStore, first, () -> {
                AwsException failure = assertThrows(AwsException.class, competing::run);
                assertEquals(expectedError, failure.getErrorCode());
            });
            assertEquals(deleteFirst ? 0 : 1, service.listBackupSelections(plan.getBackupPlanId()).size());
            assertEquals(deleteFirst ? 0 : 1, service.listBackupPlans().size());
            assertEquals(1, service.listBackupPlans(REGION, true, null, null).items().size());
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"list", "tag", "untag"})
    void planTagsUseKeyedLookupWithoutScanningRetainedHistory(String operation) {
        AccountAwareStorageBackend<BackupPlan> plans = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
        BackupService service = service(storage(plans, AccountAwareStorageBackend.inMemory(ACCOUNT)), config());
        try {
            BackupPlan retained = service.createBackupPlan("deleted", List.of(), null, REGION);
            service.deleteBackupPlan(retained.getBackupPlanId());
            BackupPlan active = service.createBackupPlan("active", List.of(), null, Map.of("env", "test"), REGION);
            clearInvocations(plans);
            switch (operation) {
                case "list" -> assertEquals(Map.of("env", "test"), service.listTags(active.getBackupPlanArn()));
                case "tag" -> {
                    service.tagResource(active.getBackupPlanArn(), Map.of("new", "value"));
                    assertEquals(Map.of("env", "test", "new", "value"), service.listTags(active.getBackupPlanArn()));
                }
                case "untag" -> {
                    service.untagResource(active.getBackupPlanArn(), List.of("env"));
                    assertTrue(service.listTags(active.getBackupPlanArn()).isEmpty());
                }
                default -> throw new IllegalArgumentException(operation);
            }
            verify(plans, never()).scan(any());
            verify(plans, atLeastOnce()).get(active.getBackupPlanId());
        } finally {
            service.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"account", "region", "partition", "service", "type", "malformed", "deleted"})
    void keyedPlanTagsStillRequireExactActiveArn(String mismatch) {
        AccountAwareStorageBackend<BackupPlan> plans = spy(AccountAwareStorageBackend.inMemory(ACCOUNT));
        BackupService service = service(storage(plans, AccountAwareStorageBackend.inMemory(ACCOUNT)), config());
        try {
            BackupPlan plan = service.createBackupPlan("exact", List.of(), null, Map.of("env", "test"), REGION);
            String arn = switch (mismatch) {
                case "account" -> plan.getBackupPlanArn().replace(ACCOUNT, "999999999999");
                case "region" -> plan.getBackupPlanArn().replace(REGION, "us-east-1");
                case "partition" -> plan.getBackupPlanArn().replace("arn:aws:", "arn:aws-cn:");
                case "service" -> plan.getBackupPlanArn().replace(":backup:", ":other:");
                case "type" -> plan.getBackupPlanArn().replace("backup-plan:", "backup-vault:");
                case "malformed" -> "not-an-arn";
                case "deleted" -> service.deleteBackupPlan(plan.getBackupPlanId()).getBackupPlanArn();
                default -> throw new IllegalArgumentException(mismatch);
            };
            assertNotFound(() -> service.listTags(arn));
            assertNotFound(() -> service.tagResource(arn, Map.of("late", "tag")));
            assertNotFound(() -> service.untagResource(arn, List.of("env")));
            assertEquals(Map.of("env", "test"), plans.get(plan.getBackupPlanId()).orElseThrow().getTags());
        } finally {
            service.shutdown();
        }
    }

    private static void assertNotFound(Runnable operation) {
        assertEquals("ResourceNotFoundException", assertThrows(AwsException.class, operation::run).getErrorCode());
    }

    private static EmulatorConfig config() {
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.defaultAccountId()).thenReturn(ACCOUNT);
        when(config.services().backup().jobCompletionDelaySeconds()).thenReturn(3600);
        return config;
    }

    private static StorageFactory storage(AccountAwareStorageBackend<BackupPlan> plans,
                                          AccountAwareStorageBackend<BackupSelection> selections) {
        StorageFactory storage = mock(StorageFactory.class);
        when(storage.create(eq("backup"), anyString(), any())).thenAnswer(call -> switch ((String) call.getArgument(1)) {
            case "backup-plans.json" -> plans;
            case "backup-selections.json" -> selections;
            default -> AccountAwareStorageBackend.inMemory(ACCOUNT);
        });
        return storage;
    }

    private static BackupService service(StorageFactory storage, EmulatorConfig config) {
        return new BackupService(storage, config, new RegionResolver(REGION, ACCOUNT), new ObjectMapper(),
                mock(SnsService.class));
    }
}
