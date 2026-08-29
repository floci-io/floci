package io.github.hectorvent.floci.services.docdb;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.docdb.container.DocDbContainerManager;
import io.github.hectorvent.floci.services.docdb.model.DocDbCluster;
import io.github.hectorvent.floci.services.docdb.model.DocDbInstance;
import org.junit.jupiter.api.Test;
import io.github.hectorvent.floci.services.ec2.Ec2Service;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.rds.RdsService;
import org.mockito.Mockito;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Moving a record under its regional key must not undo a delete.
 *
 * <p>The move is a read-modify-write like a tag update: read the record from the unscoped key,
 * write it under the regional one. A delete landing between the two would be undone by that write,
 * leaving a cluster no describe created and no delete can remove — so the move takes the record's
 * monitor, which is the one the delete already holds.
 */
class DocDbLegacyMigrationRaceTest {

    private StorageBackend<String, DocDbCluster> clusterStore;
    private DocDbService service;

    private void serviceOver(PausingStorageBackend<DocDbCluster> pausing) {
        clusterStore = new AccountAwareStorageBackend<>(pausing, null, "000000000000");
        StorageBackend<String, DocDbInstance> instanceStore =
                AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                "docdb-clusters.json".equals(inv.getArgument(1)) ? clusterStore : instanceStore);

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(true);
        service = new DocDbService(config, new RegionResolver("us-east-1", "000000000000"),
                Mockito.mock(DocDbContainerManager.class), storageFactory,
                Mockito.mock(RdsService.class), Mockito.mock(Ec2Service.class), Mockito.mock(KmsService.class));
    }

    private void freshService() {
        clusterStore = AccountAwareStorageBackend.inMemory("000000000000");
        StorageBackend<String, DocDbInstance> instanceStore =
                AccountAwareStorageBackend.inMemory("000000000000");
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(anyString(), anyString(), any())).thenAnswer(inv ->
                "docdb-clusters.json".equals(inv.getArgument(1)) ? clusterStore : instanceStore);

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var docdbConfig = Mockito.mock(EmulatorConfig.DocDbServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.docdb()).thenReturn(docdbConfig);
        when(docdbConfig.mock()).thenReturn(true);

        service = new DocDbService(config, new RegionResolver("us-east-1", "000000000000"),
                Mockito.mock(DocDbContainerManager.class), storageFactory,
                Mockito.mock(RdsService.class), Mockito.mock(Ec2Service.class), Mockito.mock(KmsService.class));
    }

    @Test
    void readingALegacyRecordWhileItIsDeletedDoesNotBringItBack() throws Exception {
        for (int attempt = 0; attempt < 25; attempt++) {
            freshService();
            String id = "legacy-race-" + attempt;

            // A record as an earlier Floci wrote it: under the bare identifier.
            DocDbCluster legacy = new DocDbCluster();
            legacy.setDbClusterIdentifier(id);
            legacy.setStatus("available");
            clusterStore.put(id, legacy);

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread reader = new Thread(() -> {
                await(start);
                try {
                    service.getDbCluster(id);
                } catch (AwsException expected) {
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
                } catch (AwsException expected) {
                    if (!"DBClusterNotFoundFault".equals(expected.getErrorCode())) {
                        unexpected.set(expected);
                    }
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            reader.start();
            deleter.start();
            start.countDown();
            reader.join(TimeUnit.SECONDS.toMillis(10));
            deleter.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(reader.isAlive() || deleter.isAlive(), "a thread never finished");
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

            // The status is reported because it names the writer: a record put back as "deleting"
            // came from the delete's own object, one filled in on read from the reader's.
            assertFalse(clusterStore.get("us-east-1::" + id).isPresent(),
                    () -> id + " came back under its regional key with status "
                            + clusterStore.get("us-east-1::" + id).map(DocDbCluster::getStatus).orElse("?"));
            assertFalse(clusterStore.get(id).isPresent(),
                    id + " was left behind under the bare key (attempt " + attempt + ")");
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void listingStagedAcrossTheMoveSeesTheRecordExactlyOnce() throws Exception {
        // Staged rather than raced: the listing is held at its first scan while the move runs to
        // completion, which is the interleaving that loses or doubles the record.
        PausingStorageBackend<DocDbCluster> pausing =
                new PausingStorageBackend<>(new io.github.hectorvent.floci.core.storage.InMemoryStorage<>());
        serviceOver(pausing);
        String id = "listed-across-the-move";
        DocDbCluster legacy = new DocDbCluster();
        legacy.setDbClusterIdentifier(id);
        legacy.setStatus("available");
        clusterStore.put(id, legacy);

        AtomicReference<java.util.List<String>> listed = new AtomicReference<>();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        // The second scan: the first runs before the move, so holding the second is what puts the
        // move between them — the interleaving in which a record can be lost or counted twice.
        pausing.pauseOn(PausingStorageBackend.Call.SCAN, null, 1);

        Thread lister = new Thread(() -> {
            try {
                listed.set(service.listDbClusters(null).stream()
                        .map(DocDbCluster::getDbClusterIdentifier).toList());
            } catch (Throwable t) {
                unexpected.set(t);
            }
        });
        lister.start();
        pausing.awaitReached();

        // The move happens entirely inside the listing's first scan.
        service.getDbCluster(id);
        pausing.release();
        lister.join(TimeUnit.SECONDS.toMillis(10));

        assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());
        assertEquals(java.util.List.of(id), listed.get(),
                "the cluster should be listed exactly once across the move");
    }

    @Test
    void removingTheLastInstanceCannotWriteOverAClusterDelete() throws Exception {
        // Staged: the instance delete is held as it writes the cluster back without its member,
        // and the cluster delete runs to completion first. Sharing one monitor is what stops the
        // held write from landing afterwards.
        PausingStorageBackend<DocDbCluster> pausing =
                new PausingStorageBackend<>(new io.github.hectorvent.floci.core.storage.InMemoryStorage<>());
        serviceOver(pausing);
        String clusterId = "co-delete-staged";
        String instanceId = "co-delete-staged-instance";
        service.createDbCluster(clusterId, "5.0.0", "admin", "secret99password", false);
        service.createDbInstance(instanceId, clusterId, "db.r5.large", "5.0.0", false);

        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        pausing.pauseOn(PausingStorageBackend.Call.PUT, "us-east-1::" + clusterId);

        Thread instanceDeleter = new Thread(() -> {
            try {
                service.deleteDbInstance(instanceId);
            } catch (Throwable t) {
                unexpected.set(t);
            }
        });
        instanceDeleter.start();
        pausing.awaitReached();

        // Held mid-write, the instance is already out of the store, so the cluster deletes.
        Thread clusterDeleter = new Thread(() -> {
            try {
                service.deleteDbCluster(clusterId);
            } catch (Throwable t) {
                unexpected.set(t);
            }
        });
        clusterDeleter.start();
        clusterDeleter.join(TimeUnit.SECONDS.toMillis(2));

        pausing.release();
        instanceDeleter.join(TimeUnit.SECONDS.toMillis(10));
        clusterDeleter.join(TimeUnit.SECONDS.toMillis(10));
        assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

        assertFalse(clusterStore.get("us-east-1::" + clusterId).isPresent(),
                "the member-list write landed after the cluster was deleted");
    }


    @Test
    void listingWhileALegacyRecordIsMovedSeesItExactlyOnce() throws Exception {
        // The listing reads two sets of keys while a migration is moving a record between them.
        // Whichever way the two scans straddle the move, the record has to appear once: missing it
        // hides a resource that exists, and seeing it twice reports one resource as two.
        for (int attempt = 0; attempt < 25; attempt++) {
            freshService();
            String id = "listed-during-move-" + attempt;
            DocDbCluster legacy = new DocDbCluster();
            legacy.setDbClusterIdentifier(id);
            legacy.setStatus("available");
            clusterStore.put(id, legacy);

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();
            AtomicReference<java.util.List<String>> listed = new AtomicReference<>();

            Thread mover = new Thread(() -> {
                await(start);
                try {
                    service.getDbCluster(id);
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            Thread lister = new Thread(() -> {
                await(start);
                try {
                    listed.set(service.listDbClusters(null).stream()
                            .map(DocDbCluster::getDbClusterIdentifier).toList());
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            mover.start();
            lister.start();
            start.countDown();
            mover.join(TimeUnit.SECONDS.toMillis(10));
            lister.join(TimeUnit.SECONDS.toMillis(10));
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());
            assertEquals(java.util.List.of(id), listed.get(),
                    "the cluster should be listed exactly once, attempt " + attempt);
        }
    }

    @Test
    void deletingTheLastInstanceWhileItsClusterIsDeletedLeavesNothingBehind() throws Exception {
        // Both paths write the cluster record — one removes the instance from its member list,
        // the other deletes it — so they have to take the same monitor. Naming one of them after
        // the bare identifier is a second monitor, and the member update then lands after the
        // delete, leaving cluster metadata no delete can remove.
        for (int attempt = 0; attempt < 25; attempt++) {
            freshService();
            String clusterId = "co-delete-" + attempt;
            String instanceId = "co-delete-instance-" + attempt;
            service.createDbCluster(clusterId, "5.0.0", "admin", "secret99password", false);
            service.createDbInstance(instanceId, clusterId, "db.r5.large", "5.0.0", false);

            CountDownLatch start = new CountDownLatch(1);
            AtomicReference<Throwable> unexpected = new AtomicReference<>();

            Thread instanceDeleter = new Thread(() -> {
                await(start);
                try {
                    service.deleteDbInstance(instanceId);
                } catch (AwsException expected) {
                    // losing the race is fine; answering wrongly is not
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });
            Thread clusterDeleter = new Thread(() -> {
                await(start);
                try {
                    service.deleteDbCluster(clusterId);
                } catch (AwsException expected) {
                    // the cluster still has a member until the other thread finishes
                } catch (Throwable t) {
                    unexpected.set(t);
                }
            });

            instanceDeleter.start();
            clusterDeleter.start();
            start.countDown();
            instanceDeleter.join(TimeUnit.SECONDS.toMillis(10));
            clusterDeleter.join(TimeUnit.SECONDS.toMillis(10));
            assertFalse(instanceDeleter.isAlive() || clusterDeleter.isAlive(), "a thread never finished");
            assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

            // Whoever won, the instance is gone and the cluster is either gone or still deletable.
            assertFalse(clusterStore.get("us-east-1::" + clusterId)
                            .map(c -> c.getDbClusterMembers().contains(instanceId)).orElse(false),
                    "the deleted instance is still a member of " + clusterId);
            if (clusterStore.get("us-east-1::" + clusterId).isPresent()) {
                service.deleteDbCluster(clusterId);
            }
            assertFalse(clusterStore.get("us-east-1::" + clusterId).isPresent(),
                    clusterId + " could not be removed after the race (attempt " + attempt + ")");
        }
    }

    @Test
    void aDeleteCannotSlipBetweenTheMovesReadAndItsWrite() throws Exception {
        // Staged, because the racing loop above does not reach this one: the move reads the record
        // from the unscoped key and writes it under the regional one, and a delete landing between
        // those two would be undone by that write. Holding the move at its write and letting a
        // delete run is that interleaving exactly.
        PausingStorageBackend<DocDbCluster> pausing =
                new PausingStorageBackend<>(new io.github.hectorvent.floci.core.storage.InMemoryStorage<>());
        serviceOver(pausing);
        String id = "moved-while-deleted";
        DocDbCluster legacy = new DocDbCluster();
        legacy.setDbClusterIdentifier(id);
        legacy.setStatus("available");
        clusterStore.put(id, legacy);

        AtomicReference<Throwable> unexpected = new AtomicReference<>();
        pausing.pauseOn(PausingStorageBackend.Call.PUT, "us-east-1::" + id);

        Thread mover = new Thread(() -> {
            try {
                service.getDbCluster(id);
            } catch (AwsException expected) {
                // losing to the delete is a legitimate outcome
            } catch (Throwable t) {
                unexpected.set(t);
            }
        });
        mover.start();
        pausing.awaitReached();

        Thread deleter = new Thread(() -> {
            try {
                service.deleteDbCluster(id);
            } catch (AwsException expected) {
                // so is finding it already gone
            } catch (Throwable t) {
                unexpected.set(t);
            }
        });
        deleter.start();
        // Sharing the monitor is what stops the delete finishing here: it should still be waiting.
        deleter.join(TimeUnit.SECONDS.toMillis(1));

        pausing.release();
        mover.join(TimeUnit.SECONDS.toMillis(10));
        deleter.join(TimeUnit.SECONDS.toMillis(10));
        assertNull(unexpected.get(), () -> "unexpected failure: " + unexpected.get());

        assertFalse(clusterStore.get("us-east-1::" + id).isPresent(),
                "the move wrote the record back after it was deleted");
        assertFalse(clusterStore.get(id).isPresent(), "the unscoped key was left behind");
    }
}
