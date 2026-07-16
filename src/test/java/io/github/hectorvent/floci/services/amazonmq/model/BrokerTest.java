package io.github.hectorvent.floci.services.amazonmq.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BrokerTest {

    /**
     * containerId / accountId / volumeId are internal bookkeeping but must survive a
     * serialize -> deserialize round-trip so the broker stays manageable after an
     * emulator restart (volume cleanup, container teardown, account-aware routing).
     * They are kept out of the API by the controller's explicit DescribeBroker
     * response, not by dropping them from storage.
     */
    @Test
    void internalFieldsPersistAcrossSerialization() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        Broker broker = new Broker("b-1", "arn:aws:mq:us-east-1:000000000000:broker:n:b-1",
                "n", "RABBITMQ", "3.13", "SINGLE_INSTANCE", "mq.t3.micro");
        broker.setContainerId("container-xyz");
        broker.setAccountId("000000000000");
        broker.setVolumeId("a1b2c3");

        Broker roundTripped = mapper.readValue(mapper.writeValueAsString(broker), Broker.class);

        assertEquals("container-xyz", roundTripped.getContainerId());
        assertEquals("000000000000", roundTripped.getAccountId());
        assertEquals("a1b2c3", roundTripped.getVolumeId());
    }
}
