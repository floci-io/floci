package io.github.hectorvent.floci.services.autoscaling;

import io.github.hectorvent.floci.core.common.RequestScopes;
import io.github.hectorvent.floci.services.autoscaling.model.AsgOptionalFields;
import io.github.hectorvent.floci.services.autoscaling.model.AutoScalingGroup;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class AutoScalingCapacityAccountLockIntegrationTest {
    private static final String ACCOUNT = "111111111111";
    private static final String REGION = "us-east-1";

    @Inject
    AutoScalingService service;

    @ParameterizedTest
    @CsvSource({"222222222222,us-east-1", "111111111111,us-west-2"})
    void unrelatedAccountOrRegionDoesNotWaitForCapacityOperation(String otherAccount, String otherRegion)
            throws Exception {
        String name = "capacity-lock-" + UUID.randomUUID();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            AutoScalingGroup group = RequestScopes.callAs(ACCOUNT, REGION, () -> create(REGION, name));
            RequestScopes.runAs(otherAccount, otherRegion, () -> create(otherRegion, name));
            Future<?> adjustment = workers.submit(() -> RequestScopes.runAs(ACCOUNT, REGION,
                    () -> service.reconcileIfPresent(group, () -> {
                        entered.countDown();
                        try {
                            assertTrue(release.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(e);
                        }
                    })));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<?> suspension = workers.submit(() -> RequestScopes.runAs(otherAccount, otherRegion,
                    () -> service.suspendProcesses(otherRegion, name, List.of("Launch"))));
            suspension.get(2, TimeUnit.SECONDS);
            RequestScopes.runAs(otherAccount, otherRegion, () -> assertTrue(service
                    .describeAutoScalingGroups(otherRegion, List.of(name)).getFirst()
                    .getSuspendedProcesses().contains("Launch")));
            release.countDown();
            adjustment.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
            try {
                RequestScopes.runAs(ACCOUNT, REGION, () -> service.deleteAutoScalingGroup(REGION, name, true));
            } finally {
                RequestScopes.runAs(otherAccount, otherRegion,
                        () -> service.deleteAutoScalingGroup(otherRegion, name, true));
            }
        }
    }

    private AutoScalingGroup create(String region, String name) {
        service.createAutoScalingGroup(region, name, "unused-capacity-lock-source", null, null, null, null,
                0, 1, 0, 300, List.of(region + "a"), List.of(), List.of(), List.of(), "EC2", 0,
                List.of("Default"), Map.of(), Map.of(), AsgOptionalFields.none());
        return service.describeAutoScalingGroups(region, List.of(name)).getFirst();
    }
}
