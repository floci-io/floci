package io.github.hectorvent.floci.services.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.backup.model.BackupPlan;
import io.github.hectorvent.floci.services.sns.SnsService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BackupPlanConcurrencyTest {
    @ParameterizedTest
    @ValueSource(strings = {"tag", "untag", "update"})
    void overlappingWritesCannotRecreateADeletedPlan(String operation) throws Exception {
        String account = "741677900003";
        AccountAwareStorageBackend<BackupPlan> plans = spy(AccountAwareStorageBackend.inMemory(account));
        StorageFactory storage = mock(StorageFactory.class);
        when(storage.create(eq("backup"), anyString(), any())).thenAnswer(call ->
                "backup-plans.json".equals(call.getArgument(1))
                        ? plans : AccountAwareStorageBackend.inMemory(account));
        EmulatorConfig config = mock(EmulatorConfig.class, RETURNS_DEEP_STUBS);
        when(config.services().backup().jobCompletionDelaySeconds()).thenReturn(3600);
        BackupService service = new BackupService(storage, config, new RegionResolver("eu-west-2", account),
                new ObjectMapper(), mock(SnsService.class));
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            BackupPlan plan = service.createBackupPlan("overlap", List.of(), null, Map.of("env", "old"), "eu-west-2");
            String id = plan.getBackupPlanId();
            doAnswer(call -> {
                writing.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return call.callRealMethod();
            }).when(plans).put(eq(id), any());
            FutureTask<Void> write = new FutureTask<>(() -> {
                Runnable mutation = switch (operation) {
                    case "tag" -> () -> service.tagResource(plan.getBackupPlanArn(), Map.of("env", "new"));
                    case "untag" -> () -> service.untagResource(plan.getBackupPlanArn(), List.of("env"));
                    case "update" -> () -> service.updateBackupPlan(id, "updated", List.of());
                    default -> throw new AssertionError(operation);
                };
                mutation.run();
                return null;
            });
            Thread writer = new Thread(write, "backup-plan-write");
            writer.setDaemon(true);
            writer.start();
            assertTrue(writing.await(5, TimeUnit.SECONDS));
            FutureTask<Void> delete = new FutureTask<>(() -> {
                service.deleteBackupPlan(id);
                return null;
            });
            Thread deleter = new Thread(delete, "backup-plan-delete");
            deleter.setDaemon(true);
            deleter.start();
            await().atMost(Duration.ofSeconds(5)).until(() ->
                    deleter.getState() == Thread.State.BLOCKED || delete.isDone());
            release.countDown();
            write.get(5, TimeUnit.SECONDS);
            delete.get(5, TimeUnit.SECONDS);
            assertEquals("ResourceNotFoundException", assertThrows(AwsException.class,
                    () -> service.getBackupPlan(id)).getErrorCode());
            assertTrue(service.listBackupPlans().isEmpty());
        } finally {
            release.countDown();
            service.shutdown();
        }
    }
}
