package io.github.hectorvent.floci.services.amazonmq;

import io.github.hectorvent.floci.config.EmulatorConfig;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.services.amazonmq.container.RabbitMqManager;
import io.github.hectorvent.floci.services.amazonmq.model.Broker;
import io.github.hectorvent.floci.services.amazonmq.model.MqUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * EngineType casing on CreateBroker. Callers spell the engine the way the AWS
 * documentation and console do -- "RabbitMQ" -- and SDK clients transmit that string
 * as given, so an exact match against the enum's uppercase form rejects the spelling
 * nearly every caller uses.
 */
class AmazonMqEngineTypeCaseTest {

    private AmazonMqService service;

    @BeforeEach
    void setUp() {
        StorageFactory storageFactory = Mockito.mock(StorageFactory.class);
        when(storageFactory.create(Mockito.anyString(), Mockito.anyString(), Mockito.any()))
                .thenReturn(AccountAwareStorageBackend.inMemory("000000000000"));

        EmulatorConfig config = Mockito.mock(EmulatorConfig.class);
        var servicesConfig = Mockito.mock(EmulatorConfig.ServicesConfig.class);
        var mqConfig = Mockito.mock(EmulatorConfig.AmazonMqServiceConfig.class);
        when(config.services()).thenReturn(servicesConfig);
        when(servicesConfig.amazonmq()).thenReturn(mqConfig);
        when(mqConfig.mock()).thenReturn(true);
        when(config.defaultRegion()).thenReturn("us-east-1");

        service = new AmazonMqService(storageFactory, config,
                new RegionResolver("us-east-1", "000000000000"),
                Mockito.mock(RabbitMqManager.class));
    }

    private CreateBrokerParams params(String name, String engineType) {
        return new CreateBrokerParams(name, engineType, null, "SINGLE_INSTANCE",
                "mq.t3.micro", false, false,
                List.of(new MqUser("admin", "AdminPass123", true, null)), null);
    }

    @ParameterizedTest(name = "engineType \"{0}\" is accepted")
    @ValueSource(strings = {"RABBITMQ", "RabbitMQ", "rabbitmq", "rAbBiTmQ"})
    void engineTypeIsMatchedWithoutRegardToCase(String engineType) {
        Broker broker = service.createBroker(params("broker-" + engineType.toLowerCase(), engineType));
        assertEquals("broker-" + engineType.toLowerCase(), broker.getBrokerName());
    }

    @Test
    void theEngineTypeIsReportedInItsCanonicalUppercaseForm() {
        // However the caller spelled it, the broker reports the enum value, so a client
        // comparing against RABBITMQ sees what it expects.
        Broker broker = service.createBroker(params("canonical", "RabbitMQ"));
        assertEquals("RABBITMQ", broker.getEngineType());
    }

    @Test
    void activeMqIsRejectedWithAMessageNamingTheEngine() {
        // ActiveMQ is a genuine capability gap rather than a malformed request. The
        // message has to say which engine was refused, otherwise it is indistinguishable
        // from the casing rejection this test class exists to prevent.
        AwsException e = assertThrows(AwsException.class,
                () -> service.createBroker(params("activemq-broker", "ActiveMQ")));
        assertEquals(400, e.getHttpStatus());
        assertTrue(e.getMessage().contains("ActiveMQ"),
                "expected the refused engine to be named, got: " + e.getMessage());
    }

    @Test
    void anUnknownEngineIsRejected() {
        AwsException e = assertThrows(AwsException.class,
                () -> service.createBroker(params("nonsense-broker", "KAFKA")));
        assertEquals(400, e.getHttpStatus());
    }
}
