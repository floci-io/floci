package io.github.hectorvent.floci.services.autoscaling;

import com.fasterxml.jackson.core.type.TypeReference;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.core.storage.WalStorage;
import io.github.hectorvent.floci.services.autoscaling.model.AsgInstance;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.elbv2.ElbV2HealthChecker;
import io.github.hectorvent.floci.services.elbv2.ElbV2Service;
import io.github.hectorvent.floci.services.elbv2.model.TargetDescription;
import io.github.hectorvent.floci.services.elbv2.model.TargetGroup;
import io.github.hectorvent.floci.testing.StorageFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AutoScalingTargetCleanupStorageTest {

    private static final String REGION = "us-east-1";
    private static final String ACCOUNT = "000000000000";
    private static final String FIRST = "first-target-group";
    private static final String SECOND = "second-target-group";
    private static final TypeReference<Map<String, Map<String, TargetGroup>>> TARGETS = new TypeReference<>() {};

    @TempDir Path directory;
    private StorageFactory factory;

    @Test
    void failedSecondTargetWriteRetainsDurableOwnershipAndRetriesWithoutRemovingForeignTargets() throws Exception {
        factory = StorageFactories.open(directory, "wal", ACCOUNT);
        AccountAwareStorageBackend<Map<String, TargetGroup>> targets = targetStore();
        Map<String, TargetGroup> groups = new LinkedHashMap<>();
        groups.put(FIRST, targetGroup(FIRST));
        groups.put(SECOND, targetGroup(SECOND));
        targets.put(REGION, groups);
        AutoScalingService scaling = scalingService();
        AutoScalingGroup group = new AutoScalingGroup();
        group.setRegion(REGION);
        group.setAutoScalingGroupName("cleanup-group");
        group.setDesiredCapacity(0);
        group.setTargetGroupARNs(List.of(FIRST, SECOND));
        AsgInstance instance = new AsgInstance();
        instance.setInstanceId("i-owned");
        instance.setLifecycleState("InService");
        group.getInstances().add(instance);
        scaling.groups.put(REGION + "::cleanup-group", group);
        ElbV2Service elb = spy(elbService());
        doAnswer(invocation -> {
            failNextTargetWrite(targets);
            return invocation.callRealMethod();
        }).when(elb).deregisterTargets(eq(REGION), eq(SECOND), anyList());
        AutoScalingReconciler reconciler = new AutoScalingReconciler(scaling, mock(Ec2Service.class), elb);

        assertThrows(UncheckedIOException.class, () -> reconciler.reconcile(group));

        // Close WAL writers without snapshot compaction, then replay only what the disk accepted.
        closeWithoutCompaction();
        factory = StorageFactories.open(directory, "wal", ACCOUNT);
        assertEquals(List.of("i-foreign"), targetIds(FIRST));
        assertEquals(List.of("i-owned", "i-foreign"), targetIds(SECOND));
        scaling = scalingService();
        AutoScalingGroup restored = scaling.requireGroup(REGION, "cleanup-group");
        assertEquals(List.of("i-owned"), restored.getInstances().stream().map(AsgInstance::getInstanceId).toList());
        new AutoScalingReconciler(scaling, mock(Ec2Service.class), elbService()).reconcile(restored);
        factory.shutdownAll();
        factory = StorageFactories.open(directory, "wal", ACCOUNT);

        assertTrue(scalingService().requireGroup(REGION, "cleanup-group").getInstances().isEmpty());
        assertEquals(List.of("i-foreign"), targetIds(FIRST));
        assertEquals(List.of("i-foreign"), targetIds(SECOND));
    }

    private AutoScalingService scalingService() {
        AutoScalingService service = new AutoScalingService();
        service.regionResolver = new RegionResolver(REGION, ACCOUNT);
        service.storageFactory = factory;
        service.initializeStorage();
        return service;
    }

    private ElbV2Service elbService() throws Exception {
        ElbV2Service service = new ElbV2Service();
        field(ElbV2Service.class, "storageFactory").set(service, factory);
        field(ElbV2Service.class, "healthChecker").set(service, mock(ElbV2HealthChecker.class));
        Method initialize = ElbV2Service.class.getDeclaredMethod("initializeStorage");
        initialize.setAccessible(true);
        initialize.invoke(service);
        return service;
    }

    private AccountAwareStorageBackend<Map<String, TargetGroup>> targetStore() {
        return factory.create("elbv2", "elbv2-target-groups.json", TARGETS);
    }

    private List<String> targetIds(String arn) {
        return targetStore().get(REGION).orElseThrow().get(arn).getTargets().stream()
                .map(TargetDescription::getId).toList();
    }

    private static TargetGroup targetGroup(String arn) {
        TargetGroup group = new TargetGroup();
        group.setTargetGroupArn(arn);
        group.setTargetType("instance");
        group.setPort(80);
        List<TargetDescription> targets = new ArrayList<>();
        for (String id : List.of("i-owned", "i-foreign")) {
            TargetDescription target = new TargetDescription();
            target.setId(id);
            target.setPort(80);
            targets.add(target);
        }
        group.setTargets(targets);
        return group;
    }

    private static void failNextTargetWrite(AccountAwareStorageBackend<?> store) throws Exception {
        Object wal = field(AccountAwareStorageBackend.class, "delegate").get(store);
        Field writer = field(WalStorage.class, "walWriter");
        ((DataOutputStream) writer.get(wal)).close();
        writer.set(wal, new DataOutputStream(OutputStream.nullOutputStream()) {
            @Override
            public void flush() throws IOException {
                throw new IOException("target WAL flush failed");
            }
        });
    }

    private void closeWithoutCompaction() throws Exception {
        List<?> stores = (List<?>) field(StorageFactory.class, "walBackends").get(factory);
        for (Object store : stores) {
            ((WalStorage<?, ?>) store).shutdown();
        }
        factory = null;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @AfterEach
    void closeStorage() {
        if (factory != null) {
            factory.shutdownAll();
        }
    }
}
