package io.floci.aws.services.amazonmq;

import io.floci.aws.config.EmulatorConfig;
import io.floci.aws.core.common.AwsException;
import io.floci.aws.core.common.RegionResolver;
import io.floci.aws.core.storage.AccountAwareStorageBackend;
import io.floci.aws.core.storage.StorageFactory;
import io.floci.aws.services.amazonmq.container.RabbitMqManager;
import io.floci.aws.services.amazonmq.model.Broker;
import io.floci.aws.services.amazonmq.model.BrokerState;
import io.floci.aws.services.amazonmq.model.MqUser;
import io.floci.aws.testing.ParkingStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

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
        assertEquals("NotFoundException",
                assertThrows(AwsException.class, () -> service.describeBroker(brokerId)).getErrorCode());
        assertEquals("NotFoundException",
                assertThrows(AwsException.class, () -> service.rebootBroker(brokerId)).getErrorCode());
        assertEquals("NotFoundException",
                assertThrows(AwsException.class, () -> service.deleteBroker(brokerId)).getErrorCode());

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
