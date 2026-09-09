package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.core.common.AwsException;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tagging a cluster while it is being deleted must not put it back.
 *
 * <p>A tag update is a read-modify-write. Without a monitor shared with delete, the read can see a
 * live cluster, the delete can remove it, and the write then stores the record again — a cluster
 * that no describe created and no delete can remove.
 */
@QuarkusTest
@TestProfile(DocDbTagConcurrencyTest.NoContainersProfile.class)
class DocDbTagConcurrencyTest {

    /** The race is in storage, not in the container: starting one per attempt only adds seconds. */
    public static class NoContainersProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.docdb.mock", "true");
        }
    }

    @Inject
    DocDbService service;

    @Test
    void taggingDuringADeleteDoesNotReviveTheCluster() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            String id = "race-cluster-" + attempt;
            service.createDbCluster(id, "5.0.0", "admin", "secret99password", false);
            String arn = service.getDbCluster(id).getDbClusterArn();

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread tagger = new Thread(() -> {
                await(start);
                try {
                    service.addTagsToResource(arn, Map.of("env", "race"));
                } catch (AwsException expected) {
                    // Losing to the delete is a legitimate outcome; being answered is not.
                    if (!"DBClusterNotFoundFault".equals(expected.getErrorCode())) {
                        unexpected.set(expected);
                    }
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            Thread deleter = new Thread(() -> {
                await(start);
                try {
                    service.deleteDbCluster(id);
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            tagger.start();
            deleter.start();
            start.countDown();
            tagger.join(TimeUnit.SECONDS.toMillis(10));
            deleter.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(tagger.isAlive(), "tagger did not finish — deadlock between the monitors");
            assertFalse(deleter.isAlive(), "deleter did not finish — deadlock between the monitors");

            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());
            assertFalse(service.hasCluster(id),
                    "cluster " + id + " came back after being deleted (attempt " + attempt + ")");
        }
    }

    @Test
    void anInstanceAndItsClusterCanBeTaggedAtTheSameTime() throws Exception {
        // The two paths take the monitors in one order everywhere; taking them in opposite
        // orders would hang here rather than fail an assertion.
        String clusterId = "lock-order-cluster";
        String instanceId = "lock-order-instance";
        service.createDbCluster(clusterId, "5.0.0", "admin", "secret99password", false);
        service.createDbInstance(instanceId, clusterId, "db.r5.large", "5.0.0", false);
        String clusterArn = service.getDbCluster(clusterId).getDbClusterArn();
        String instanceArn = service.getDbInstance(instanceId).getDbInstanceArn();

        CountDownLatch start = new CountDownLatch(1);
        Thread one = new Thread(() -> {
            await(start);
            service.addTagsToResource(clusterArn, Map.of("a", "1"));
        });
        Thread two = new Thread(() -> {
            await(start);
            service.addTagsToResource(instanceArn, Map.of("b", "2"));
        });
        one.start();
        two.start();
        start.countDown();
        one.join(TimeUnit.SECONDS.toMillis(10));
        two.join(TimeUnit.SECONDS.toMillis(10));

        assertFalse(one.isAlive() || two.isAlive(), "a tag update never finished");
        assertTrue(service.listTagsForResource(clusterArn).containsKey("a"));
        assertTrue(service.listTagsForResource(instanceArn).containsKey("b"));

        service.deleteDbInstance(instanceId);
        service.deleteDbCluster(clusterId);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
