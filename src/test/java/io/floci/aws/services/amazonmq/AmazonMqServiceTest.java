package io.floci.aws.services.amazonmq;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.amazonmq.container.RabbitMqManager;
import io.floci.aws.services.amazonmq.model.Broker;
import io.floci.aws.services.amazonmq.model.BrokerInstance;
import io.floci.aws.services.amazonmq.model.BrokerState;
import io.floci.aws.services.amazonmq.model.MqUser;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import static io.floci.aws.testing.ParkingStoreAssertions.assertOperationWaitsForParkedStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

class AmazonMqServiceTest {

    private static final String ACCOUNT = "000000000000";

    private AccountAwareStorageBackend<Broker> brokers;
    private RegionResolver regionResolver;
    private AmazonMqService service;

    @BeforeEach
    void setUp() {
        brokers = AccountAwareStorageBackend.inMemory(ACCOUNT);
        regionResolver = Mockito.spy(new RegionResolver("us-east-1", ACCOUNT));
        service = newService(true, Mockito.mock(RabbitMqManager.class));
    }

    @AfterEach
    void stopPoller() {
        service.shutdown();
    }

    private CreateBrokerParams rabbitParams(String name) {
        return new CreateBrokerParams(name, "RABBITMQ", null, "SINGLE_INSTANCE",
                "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "AdminPass123", true, null)), null);
    }

    @Test
    void createBrokerComesUpRunningWithEndpoints() {
        Broker broker = service.createBroker(rabbitParams("orders"));

        assertEquals("orders", broker.getBrokerName());
        assertEquals("RABBITMQ", broker.getEngineType());
        assertEquals(BrokerState.RUNNING, broker.getBrokerState());
        assertTrue(broker.getBrokerId().startsWith("b-"));
        assertTrue(broker.getBrokerArn().contains(":mq:"));
        assertTrue(broker.getBrokerArn().contains("orders"));
        assertFalse(broker.getBrokerInstances().isEmpty());
        assertFalse(broker.getBrokerInstances().get(0).getEndpoints().isEmpty());
    }

    @Test
    void createBrokerDefaultsEngineVersionWhenAbsent() {
        Broker broker = service.createBroker(rabbitParams("orders"));
        assertEquals("3.13", broker.getEngineVersion());
    }

    @Test
    void createBrokerRejectsNonRabbitEngine() {
        CreateBrokerParams activeMq = new CreateBrokerParams("legacy", "ACTIVEMQ", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false, null, null);
        assertThrows(AwsException.class, () -> service.createBroker(activeMq));
    }

    @Test
    void createBrokerAcceptsDeploymentModeCasing() {
        // DeploymentMode is matched case-insensitively for the same reason EngineType is: the
        // wire enum is upper case but callers spell it however their tooling does, and
        // "single_instance" names the one mode this emulator supports rather than one to
        // reject. The canonical wire casing is stored so DescribeBroker reads back the enum
        // value the SDKs expect, whatever casing the request used.
        CreateBrokerParams mixedCase = new CreateBrokerParams("orders", "RABBITMQ", null,
                "single_instance", "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "AdminPass123", true, null)), null);
        Broker broker = service.createBroker(mixedCase);
        assertEquals("SINGLE_INSTANCE", broker.getDeploymentMode());
    }

    @Test
    void createBrokerRejectsNonSingleInstanceDeployment() {
        CreateBrokerParams cluster = new CreateBrokerParams("ha", "RABBITMQ", null,
                "CLUSTER_MULTI_AZ", "mq.t3.micro", false, false, null, null);
        assertThrows(AwsException.class, () -> service.createBroker(cluster));
    }

    @Test
    void createBrokerRejectsDuplicateName() {
        service.createBroker(rabbitParams("orders"));
        assertThrows(AwsException.class, () -> service.createBroker(rabbitParams("orders")));
    }

    @Test
    void describeBrokerThrowsWhenMissing() {
        assertThrows(AwsException.class, () -> service.describeBroker("b-does-not-exist"));
    }

    @Test
    void deleteBrokerRemovesIt() {
        Broker broker = service.createBroker(rabbitParams("orders"));
        service.deleteBroker(broker.getBrokerId());
        assertTrue(service.listBrokers().isEmpty());
    }

    @Test
    void userApiRejectedForRabbitMq() {
        // The standalone User API applies only to ActiveMQ; AWS rejects it for
        // RabbitMQ brokers, and every broker we host is RabbitMQ.
        Broker broker = service.createBroker(rabbitParams("orders"));
        String id = broker.getBrokerId();

        assertThrows(AwsException.class,
                () -> service.createUser(id, new MqUser("alice", "AnotherPass99", false, null)));
        assertThrows(AwsException.class, () -> service.listUsers(id));
        assertThrows(AwsException.class, () -> service.describeUser(id, "alice"));
        assertThrows(AwsException.class, () -> service.deleteUser(id, "alice"));
    }

    @Test
    void createBrokerSeedsAdminUser() {
        Broker broker = service.createBroker(rabbitParams("orders"));
        assertEquals(1, broker.getUsers().size());
        assertEquals("admin", broker.getUsers().get(0).getUsername());
        assertEquals("AdminPass123", broker.getUsers().get(0).getPassword());
    }

    @Test
    void createBrokerRequiresExactlyOneUser() {
        CreateBrokerParams noUsers = new CreateBrokerParams("orders", "RABBITMQ", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false, null, null);
        assertThrows(AwsException.class, () -> service.createBroker(noUsers));
    }

    @Test
    void createBrokerRejectsWeakPassword() {
        CreateBrokerParams weak = new CreateBrokerParams("orders", "RABBITMQ", null,
                "SINGLE_INSTANCE", "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "short", true, null)), null);
        assertThrows(AwsException.class, () -> service.createBroker(weak));
    }

    @Test
    void createBrokerMarksFailedWhenProvisioningThrows() {
        AmazonMqService realModeService = realModeServiceWithFailingManager();

        assertThrows(AwsException.class,
                () -> realModeService.createBroker(rabbitParams("orders")));

        // The failed broker is persisted as CREATION_FAILED, not left dangling.
        List<Broker> brokers = realModeService.listBrokers();
        assertEquals(1, brokers.size());
        assertEquals(BrokerState.CREATION_FAILED, brokers.get(0).getBrokerState());
    }

    @Test
    void rebootRunningBrokerStaysRunning() {
        Broker broker = service.createBroker(rabbitParams("orders"));
        Broker rebooted = service.rebootBroker(broker.getBrokerId());
        assertEquals(BrokerState.RUNNING, rebooted.getBrokerState());
    }

    @Test
    void rebootRejectsNonRunningBroker() {
        // A failed-provisioning broker is CREATION_FAILED; AWS allows RebootBroker
        // only on a RUNNING broker, so rebooting it must throw rather than promote
        // it (with no backing container) to RUNNING.
        AmazonMqService realModeService = realModeServiceWithFailingManager();
        assertThrows(AwsException.class,
                () -> realModeService.createBroker(rabbitParams("orders")));
        Broker failed = realModeService.listBrokers().get(0);
        assertEquals(BrokerState.CREATION_FAILED, failed.getBrokerState());

        assertThrows(AwsException.class, () -> realModeService.rebootBroker(failed.getBrokerId()));
    }

    /** Brokers are Regional: another Region of the account neither lists, describes, reboots nor deletes one. */
    @Test
    void aBrokerIsSeenOnlyInItsOwnRegion() {
        String brokerId = service.createBroker(rabbitParams("orders")).getBrokerId();

        inRegion("eu-west-1");
        assertEquals(List.of(), service.listBrokers());
        for (Executable call : List.<Executable>of(
                () -> service.describeBroker(brokerId),
                () -> service.rebootBroker(brokerId),
                () -> service.deleteBroker(brokerId))) {
            assertEquals("NotFoundException", assertThrows(AwsException.class, call).getErrorCode());
        }

        inRegion("us-east-1");
        assertEquals(List.of(brokerId), service.listBrokers().stream().map(Broker::getBrokerId).toList());
        assertEquals("orders", service.describeBroker(brokerId).getBrokerName());
    }

    /**
     * A broker name is unique in the account, across its Regions, as the CreateBroker reference documents: another
     * Region refuses it, and a broker created there under another name carries that Region's ARN.
     */
    @Test
    void aBrokerNameIsUniqueAcrossTheAccountsRegions() {
        service.createBroker(rabbitParams("orders"));

        inRegion("eu-west-1");
        assertEquals("ConflictException",
                assertThrows(AwsException.class, () -> service.createBroker(rabbitParams("orders"))).getErrorCode());
        assertEquals(List.of(), service.listBrokers());
        Broker inIreland = service.createBroker(rabbitParams("payments"));
        assertEquals("arn:aws:mq:eu-west-1:" + ACCOUNT + ":broker:payments:" + inIreland.getBrokerId(),
                inIreland.getBrokerArn());
    }

    /** A broker stored before brokers were keyed by Region moves, at startup, to the Region its ARN names. */
    @Test
    void aBrokerStoredUnderItsBareIdMovesToItsArnsRegionOnStartup() {
        Broker legacy = new Broker("b-legacy", "arn:aws:mq:eu-west-1:" + ACCOUNT + ":broker:legacy:b-legacy",
                "legacy", "RABBITMQ", "3.13", "SINGLE_INSTANCE", "mq.t3.micro");
        legacy.setAccountId(ACCOUNT);
        legacy.setBrokerState(BrokerState.RUNNING);
        brokers.put("b-legacy", legacy);

        service.init();

        assertEquals(List.of(AmazonMqService.brokerKey("eu-west-1", "b-legacy")),
                List.copyOf(brokers.keys()));
        inRegion("eu-west-1");
        assertEquals("legacy", service.describeBroker("b-legacy").getBrokerName());
        inRegion("us-east-1");
        assertEquals(List.of(), service.listBrokers());
    }

    /** A broker stored before brokers were keyed by Region, and without an ARN, moves to the default Region. */
    @Test
    void aBrokerStoredUnderItsBareIdWithoutAnArnMovesToTheDefaultRegionOnStartup() {
        Mockito.doReturn("eu-central-1").when(regionResolver).getDefaultRegion();
        Broker legacy = new Broker("b-legacy", null, "legacy", "RABBITMQ", "3.13", "SINGLE_INSTANCE", "mq.t3.micro");
        legacy.setAccountId(ACCOUNT);
        legacy.setBrokerState(BrokerState.RUNNING);
        brokers.put("b-legacy", legacy);

        service.init();

        assertEquals(List.of(AmazonMqService.brokerKey("eu-central-1", "b-legacy")),
                List.copyOf(brokers.keys()));
        inRegion("eu-central-1");
        assertEquals("legacy", service.describeBroker("b-legacy").getBrokerName());
    }

    /**
     * The readiness poller writes a broker back under the account and the key it is stored under, even
     * for a record that names no account, as one written before brokers carried theirs.
     */
    @Test
    void aBrokerTurningReadyStaysInItsAccountAndRegion() {
        RabbitMqManager readyManager = Mockito.mock(RabbitMqManager.class);
        when(readyManager.isReady(Mockito.any())).thenReturn(true);
        service = newService(false, readyManager);
        Broker starting = new Broker("b-starting", "arn:aws:mq:eu-west-1:111111111111:broker:starting:b-starting",
                "starting", "RABBITMQ", "3.13", "SINGLE_INSTANCE", "mq.t3.micro");
        starting.setBrokerState(BrokerState.CREATION_IN_PROGRESS);
        String key = AmazonMqService.brokerKey("eu-west-1", "b-starting");
        brokers.putForAccount("111111111111", key, starting);

        service.pollReadiness();

        assertEquals(BrokerState.RUNNING,
                brokers.getForAccount("111111111111", key).map(Broker::getBrokerState).orElse(null));
        assertEquals(1, brokers.scanAllAccounts().size());
    }

    /**
     * A DeleteBroker landing while the readiness poller waits on the broker's container: the poller reads the broker
     * again before writing it, finds it gone, and does not store it back as RUNNING.
     */
    @Test
    void aBrokerDeletedWhileItsReadinessIsCheckedIsNotWrittenBack() {
        RabbitMqManager readyManager = Mockito.mock(RabbitMqManager.class);
        service = newService(false, readyManager);
        String brokerId = startingBroker();
        when(readyManager.isReady(Mockito.any())).thenAnswer(check -> {
            service.deleteBroker(brokerId);
            return true;
        });

        service.pollReadiness();

        assertEquals(List.of(), service.listBrokers());
        assertTrue(brokers.scanAllAccounts().isEmpty());
    }

    /**
     * A DeleteBroker arriving while the poller reads a ready broker again waits for the poller to write it back,
     * then deletes it, so the broker is not written back after the delete.
     */
    @Test
    void aDeleteWaitsWhileThePollerWritesAReadyBrokerBack() throws Exception {
        ParkingStore<Broker> parking = new ParkingStore<>();
        brokers = new AccountAwareStorageBackend<>(parking, null, ACCOUNT);
        RabbitMqManager readyManager = Mockito.mock(RabbitMqManager.class);
        service = newService(false, readyManager);
        String brokerId = startingBroker();
        // Armed once the poller has scanned, so the read it parks is the one it makes again under the lock.
        when(readyManager.isReady(Mockito.any())).thenAnswer(check -> {
            parking.parkNextRead();
            return true;
        });

        assertOperationWaitsForParkedStore(parking, service::pollReadiness, () -> service.deleteBroker(brokerId));
        assertEquals(List.of(), service.listBrokers());
    }

    /**
     * Two DeleteBroker calls of one broker: the second waits while the first, parked as it reads the broker, holds
     * the broker's lock. It then either deletes the broker too or finds it gone, and either way the broker is not
     * stored once both are done.
     */
    @Test
    void aSecondDeleteWaitsForTheFirstAndLeavesTheBrokerDeleted() throws Exception {
        ParkingStore<Broker> parking = new ParkingStore<>();
        brokers = new AccountAwareStorageBackend<>(parking, null, ACCOUNT);
        service = newService(false, Mockito.mock(RabbitMqManager.class));
        String brokerId = startingBroker();
        parking.parkNextRead();

        assertOperationWaitsForParkedStore(parking, () -> service.deleteBroker(brokerId), () -> {
            try {
                service.deleteBroker(brokerId);
            } catch (AwsException e) {
                assertEquals("NotFoundException", e.getErrorCode());
            }
        });
        assertEquals(List.of(), service.listBrokers());
        assertTrue(brokers.scanAllAccounts().isEmpty());
    }

    /** A broker still being created, stored in the caller's account and Region; its id. */
    /**
     * #5664: shutdown removes every broker's container. After a restart a broker that was running or starting goes
     * back to CREATION_IN_PROGRESS and gets a new container, written back in its own account with the new
     * container's endpoints; a failed one stays as it is. The readiness poller leaves a broker alone while its
     * restore runs: its stored endpoints are still the old container's.
     */
    @Test
    void restoringStartsARunningOrStartingBrokerAgainInItsOwnAccount() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        Mockito.doAnswer(start -> {
            Broker broker = start.getArgument(0);
            assertEquals(BrokerState.CREATION_IN_PROGRESS, broker.getBrokerState());
            service.pollReadiness();
            // The poller must not probe the old console of a broker whose restore is running.
            Mockito.verify(manager, Mockito.never()).isReady(
                    Mockito.argThat(probed -> broker.getBrokerId().equals(probed.getBrokerId())));
            broker.setContainerId("restored-container");
            broker.setBrokerInstances(List.of(new BrokerInstance("http://broker-host:15673",
                    List.of("amqp://broker-host:5673"), "broker-host")));
            return null;
        }).when(manager).restoreContainer(Mockito.any());
        service = newService(false, manager);
        String running = storedBroker("111111111111", "eu-west-1", "b-running", BrokerState.RUNNING, true);
        String starting = storedBroker(ACCOUNT, "us-east-1", "b-starting", BrokerState.CREATION_IN_PROGRESS, true);
        // CreateBroker failed for it, so it never ran under a node name.
        String failed = storedBroker("111111111111", "eu-west-1", "b-failed", BrokerState.CREATION_FAILED, false);

        service.restorePersistedRuntime().join();

        Broker restored = brokers.getForAccount("111111111111", running).orElseThrow();
        assertEquals(BrokerState.CREATION_IN_PROGRESS, restored.getBrokerState());
        assertEquals("restored-container", restored.getContainerId());
        assertEquals("amqp://broker-host:5673", restored.getBrokerInstances().get(0).getEndpoints().get(0));
        assertEquals(BrokerState.CREATION_IN_PROGRESS,
                brokers.getForAccount(ACCOUNT, starting).orElseThrow().getBrokerState());
        assertEquals(BrokerState.CREATION_FAILED,
                brokers.getForAccount("111111111111", failed).orElseThrow().getBrokerState());
        Mockito.verify(manager, Mockito.times(2)).restoreContainer(Mockito.any());
        Mockito.verify(manager, Mockito.never()).restoreContainer(
                Mockito.argThat(broker -> "b-failed".equals(broker.getBrokerId())));
    }

    /**
     * A broker whose container ran under Docker's own hostname keeps its data under a node no new container can
     * find, and its admin password was never stored, so it is reported failed rather than running without its users.
     */
    @Test
    void aBrokerFromBeforeFixedNodeNamesIsReportedFailed() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        service = newService(false, manager);
        String legacy = storedBroker(ACCOUNT, "us-east-1", "b-legacy", BrokerState.RUNNING, false);

        service.restorePersistedRuntime().join();

        Broker broker = brokers.getForAccount(ACCOUNT, legacy).orElseThrow();
        assertEquals(BrokerState.CREATION_FAILED, broker.getBrokerState());
        assertTrue(broker.getBrokerInstances().isEmpty());
        Mockito.verify(manager, Mockito.never()).restoreContainer(Mockito.any());
    }

    /** A broker a crash left being deleted is deleted with its container and volume. */
    @Test
    void aBrokerLeftBeingDeletedIsDeleted() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        service = newService(false, manager);
        String deleting = storedBroker(ACCOUNT, "us-east-1", "b-deleting", BrokerState.DELETION_IN_PROGRESS, true);

        service.restorePersistedRuntime().join();

        assertTrue(brokers.getForAccount(ACCOUNT, deleting).isEmpty());
        Mockito.verify(manager).stopContainer(Mockito.argThat(broker -> "b-deleting".equals(broker.getBrokerId())));
        Mockito.verify(manager).removeBrokerStorage(
                Mockito.argThat(broker -> "b-deleting".equals(broker.getBrokerId())));
        Mockito.verify(manager, Mockito.never()).restoreContainer(Mockito.any());
    }

    /**
     * One broker whose container cannot be started is reported failed and leaves the others to restore; its failure
     * is thrown at the end.
     */
    @Test
    void aBrokerThatCannotBeRestoredIsReportedFailedAndLeavesTheOthers() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        IllegalStateException cause = new IllegalStateException("docker unavailable");
        Mockito.doThrow(cause).when(manager).restoreContainer(
                Mockito.argThat(broker -> "b-first".equals(broker.getBrokerId())));
        service = newService(false, manager);
        String first = storedBroker(ACCOUNT, "us-east-1", "b-first", BrokerState.RUNNING, true);
        String second = storedBroker(ACCOUNT, "us-east-1", "b-second", BrokerState.RUNNING, true);

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> service.restorePersistedRuntime().join());

        assertEquals("Could not restore Amazon MQ broker us-east-1::b-first of account " + ACCOUNT,
                thrown.getCause().getMessage());
        assertSame(cause, thrown.getCause().getCause());
        Broker failedBroker = brokers.getForAccount(ACCOUNT, first).orElseThrow();
        assertEquals(BrokerState.CREATION_FAILED, failedBroker.getBrokerState());
        assertTrue(failedBroker.getBrokerInstances().isEmpty());
        Mockito.verify(manager).restoreContainer(Mockito.argThat(broker -> "b-second".equals(broker.getBrokerId())));
        assertEquals(BrokerState.CREATION_IN_PROGRESS,
                brokers.getForAccount(ACCOUNT, second).orElseThrow().getBrokerState());
    }

    /**
     * A broker whose container outlived Floci, as when Floci is killed, keeps that container when it answers on the
     * console its record names, whatever its node name: its node holds the broker's data.
     */
    @Test
    void aBrokerWhoseContainerSurvivedKeepsIt() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        when(manager.adoptRunningContainer(Mockito.any())).thenAnswer(adopt -> {
            Broker candidate = adopt.getArgument(0);
            assertEquals("http://broker-host:15672", candidate.getBrokerInstances().get(0).getConsoleURL());
            candidate.setContainerId("survivor");
            return true;
        });
        service = newService(false, manager);
        String legacy = storedBroker(ACCOUNT, "us-east-1", "b-legacy", BrokerState.RUNNING, false);
        String fixed = storedBroker(ACCOUNT, "us-east-1", "b-fixed", BrokerState.RUNNING, true);

        service.restorePersistedRuntime().join();

        for (String key : List.of(legacy, fixed)) {
            Broker broker = brokers.getForAccount(ACCOUNT, key).orElseThrow();
            assertEquals(BrokerState.RUNNING, broker.getBrokerState());
            assertEquals("survivor", broker.getContainerId());
            assertEquals("http://broker-host:15672", broker.getBrokerInstances().get(0).getConsoleURL());
        }
        Mockito.verify(manager, Mockito.never()).restoreContainer(Mockito.any());
    }

    /**
     * A broker whose restore failed, as when Docker was not ready yet, ran under the fixed node name before, so the
     * next start tries it again; a broker CreateBroker failed for never ran and stays failed.
     */
    @Test
    void aBrokerWhoseRestoreFailedIsTriedAgainAtTheNextStart() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        service = newService(false, manager);
        String restoreFailed = storedBroker(ACCOUNT, "us-east-1", "b-retry", BrokerState.CREATION_FAILED, true);

        service.restorePersistedRuntime().join();

        assertEquals(BrokerState.CREATION_IN_PROGRESS,
                brokers.getForAccount(ACCOUNT, restoreFailed).orElseThrow().getBrokerState());
        Mockito.verify(manager).restoreContainer(Mockito.argThat(broker -> "b-retry".equals(broker.getBrokerId())));
    }

    /**
     * The poller's readiness answer counts only for the console it probed: a restore that cleared or changed the
     * broker's endpoints meanwhile leaves it starting, and the poller probes the new console next.
     */
    @Test
    void aReadinessAnswerFromAConsoleTheBrokerNoLongerHasIsIgnored() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        service = newService(false, manager);
        String key = storedBroker(ACCOUNT, "us-east-1", "b-moved", BrokerState.CREATION_IN_PROGRESS, true);
        Broker stored = brokers.getForAccount(ACCOUNT, key).orElseThrow();
        when(manager.isReady(Mockito.any())).thenAnswer(check -> {
            stored.setBrokerInstances(List.of(new BrokerInstance("http://broker-host:15699",
                    List.of("amqp://broker-host:5699"), "broker-host")));
            return true;
        });

        service.pollReadiness();

        assertEquals(BrokerState.CREATION_IN_PROGRESS,
                brokers.getForAccount(ACCOUNT, key).orElseThrow().getBrokerState());
    }

    /**
     * A shutdown during the restore stops it: brokers it has not started yet, and one whose start shutdown overtook,
     * stay starting for the next start, rather than being reported failed.
     */
    @Test
    void aShutdownDuringTheRestoreLeavesItsBrokersStarting() {
        RabbitMqManager stopped = Mockito.mock(RabbitMqManager.class);
        when(stopped.isShuttingDown()).thenReturn(true);
        service = newService(false, stopped);
        String notReached = storedBroker(ACCOUNT, "us-east-1", "b-not-reached", BrokerState.RUNNING, true);

        service.restorePersistedRuntime().join();

        Broker interrupted = brokers.getForAccount(ACCOUNT, notReached).orElseThrow();
        assertEquals(BrokerState.CREATION_IN_PROGRESS, interrupted.getBrokerState());
        // Its endpoints are kept, so the next start can still probe a container that outlived Floci on them.
        assertEquals("http://broker-host:15672", interrupted.getBrokerInstances().get(0).getConsoleURL());
        Mockito.verify(stopped, Mockito.never()).restoreContainer(Mockito.any());
        RabbitMqManager nextStart = Mockito.mock(RabbitMqManager.class);
        when(nextStart.adoptRunningContainer(Mockito.argThat(candidate ->
                "http://broker-host:15672".equals(candidate.getBrokerInstances().get(0).getConsoleURL()))))
                .thenReturn(true);
        service = newService(false, nextStart);
        service.restorePersistedRuntime().join();
        assertEquals(BrokerState.RUNNING, brokers.getForAccount(ACCOUNT, notReached).orElseThrow().getBrokerState());

        RabbitMqManager overtaken = Mockito.mock(RabbitMqManager.class);
        Mockito.doThrow(new RabbitMqManager.ShuttingDownException("b-overtaken"))
                .when(overtaken).restoreContainer(Mockito.any());
        service = newService(false, overtaken);
        String overtakenKey = storedBroker(ACCOUNT, "us-east-1", "b-overtaken", BrokerState.RUNNING, true);
        brokers.deleteForAccount(ACCOUNT, notReached);

        service.restorePersistedRuntime().join();

        assertEquals(BrokerState.CREATION_IN_PROGRESS,
                brokers.getForAccount(ACCOUNT, overtakenKey).orElseThrow().getBrokerState());
    }

    /** A broker deleted while its new container starts has that container stopped and its volume removed. */
    @Test
    void aBrokerDeletedWhileItsContainerStartsLosesThatContainer() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        Mockito.doAnswer(start -> {
            Broker broker = start.getArgument(0);
            service.deleteBroker(broker.getBrokerId());
            broker.setContainerId("started-after-the-delete");
            return null;
        }).when(manager).restoreContainer(Mockito.any());
        List<String> stopped = new ArrayList<>();
        Mockito.doAnswer(stop -> stopped.add(((Broker) stop.getArgument(0)).getContainerId()))
                .when(manager).stopContainer(Mockito.any());
        service = newService(false, manager);
        storedBroker(ACCOUNT, "us-east-1", "b-raced", BrokerState.RUNNING, true);

        service.restorePersistedRuntime().join();

        assertTrue(brokers.scanAllAccounts().isEmpty());
        // The delete stops what the record names; the restore then stops the container it started.
        assertEquals(List.of("removed-on-shutdown", "started-after-the-delete"), stopped);
        Mockito.verify(manager, Mockito.times(2)).removeBrokerStorage(Mockito.any());
    }

    /**
     * The readiness poller leaves a broker alone while its restore runs, and reports it RUNNING once the restore has
     * given it the new container's console and that console answers.
     */
    @Test
    void aRestoredBrokerTurnsRunningOnceItsNewConsoleAnswers() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        when(manager.isReady(Mockito.any())).thenReturn(true);
        Mockito.doAnswer(start -> {
            Broker broker = start.getArgument(0);
            broker.setContainerId("restored-container");
            broker.setBrokerInstances(List.of(new BrokerInstance("http://broker-host:15673",
                    List.of("amqp://broker-host:5673"), "broker-host")));
            service.pollReadiness();
            return null;
        }).when(manager).restoreContainer(Mockito.any());
        service = newService(false, manager);
        String key = storedBroker(ACCOUNT, "us-east-1", "b-quick", BrokerState.RUNNING, true);

        service.restorePersistedRuntime().join();
        assertEquals(BrokerState.CREATION_IN_PROGRESS,
                brokers.getForAccount(ACCOUNT, key).orElseThrow().getBrokerState());
        service.pollReadiness();

        Broker broker = brokers.getForAccount(ACCOUNT, key).orElseThrow();
        assertEquals(BrokerState.RUNNING, broker.getBrokerState());
        assertEquals("http://broker-host:15673", broker.getBrokerInstances().get(0).getConsoleURL());
        Mockito.verify(manager, Mockito.never()).stopContainer(Mockito.any());
    }

    @Test
    void restoringLeavesMockBrokersAsTheyAre() {
        RabbitMqManager manager = Mockito.mock(RabbitMqManager.class);
        service = newService(true, manager);
        String key = storedBroker(ACCOUNT, "us-east-1", "b-mock", BrokerState.RUNNING, false);

        service.restorePersistedRuntime().join();

        assertEquals(BrokerState.RUNNING, brokers.getForAccount(ACCOUNT, key).orElseThrow().getBrokerState());
        Mockito.verifyNoInteractions(manager);
    }

    /** Stores a broker in {@code account} and {@code region}, with a fixed node name or without one. */
    private String storedBroker(String account, String region, String brokerId, BrokerState state,
                                boolean fixedNodeName) {
        Broker broker = new Broker(brokerId, "arn:aws:mq:" + region + ":" + account + ":broker:" + brokerId + ":"
                + brokerId, brokerId, "RABBITMQ", "3.13", "SINGLE_INSTANCE", "mq.t3.micro");
        broker.setBrokerState(state);
        broker.setAccountId(account);
        broker.setContainerId("removed-on-shutdown");
        broker.setBrokerInstances(List.of(new BrokerInstance("http://broker-host:15672",
                List.of("amqp://broker-host:5672"), "broker-host")));
        if (fixedNodeName) {
            broker.setNodeName("rabbit@localhost");
        }
        String key = AmazonMqService.brokerKey(region, brokerId);
        brokers.putForAccount(account, key, broker);
        return key;
    }

    private String startingBroker() {
        Broker starting = new Broker("b-starting", "arn:aws:mq:us-east-1:" + ACCOUNT + ":broker:starting:b-starting",
                "starting", "RABBITMQ", "3.13", "SINGLE_INSTANCE", "mq.t3.micro");
        starting.setBrokerState(BrokerState.CREATION_IN_PROGRESS);
        brokers.putForAccount(ACCOUNT, AmazonMqService.brokerKey("us-east-1", "b-starting"), starting);
        return "b-starting";
    }

    /**
     * Two creates of one broker name in an account: the second waits for the first, parked as it stores its
     * broker, and then finds the name taken, so only one broker has it.
     */
    @Test
    void aSecondCreateOfANameWaitsForTheFirstAndIsRefused() throws Exception {
        ParkingStore<Broker> parking = new ParkingStore<>();
        brokers = new AccountAwareStorageBackend<>(parking, null, ACCOUNT);
        service = newService(true, Mockito.mock(RabbitMqManager.class));
        parking.parkNextWrite();

        assertOperationWaitsForParkedStore(parking,
                () -> service.createBroker(rabbitParams("orders")),
                () -> assertEquals("ConflictException", assertThrows(AwsException.class,
                        () -> service.createBroker(rabbitParams("orders"))).getErrorCode()));
        assertEquals(1, service.listBrokers().size());
    }

    private AmazonMqService realModeServiceWithFailingManager() {
        RabbitMqManager failingManager = Mockito.mock(RabbitMqManager.class);
        Mockito.doThrow(new RuntimeException("docker unavailable"))
                .when(failingManager).startContainer(Mockito.any());
        return newService(false, failingManager);
    }

    /** A service over {@link #brokers} answering in the Region {@link #regionResolver} gives. */
    private AmazonMqService newService(boolean mock, RabbitMqManager rabbitMqManager) {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        Mockito.doReturn(brokers).when(storageFactory).create(Mockito.anyString(), Mockito.anyString(), Mockito.any());

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        EmulatorConfig.ServicesConfig servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        EmulatorConfig.AmazonMqServiceConfig mqConfig = Mockito.mock(EmulatorConfig.AmazonMqServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.amazonmq()).thenReturn(mqConfig);
        when(mqConfig.mock()).thenReturn(mock);
        return new AmazonMqService(storageFactory, config, regionResolver, rabbitMqManager);
    }

    private void inRegion(String region) {
        Mockito.doReturn(region).when(regionResolver).getRegion();
    }
}
