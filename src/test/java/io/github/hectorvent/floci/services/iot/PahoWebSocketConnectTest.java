package io.github.hectorvent.floci.services.iot;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PahoWebSocketConnectTest {

    private static final String RACE = "WebSocket Response header: Incorrect Sec-WebSocket-Key";

    @Test
    void theHandshakeRaceIsRetriedUntilTheConnectSucceeds() throws Exception {
        ScriptedClient client = new ScriptedClient(List.of(race(), race()));

        PahoWebSocketConnect.connect(client, new MqttConnectOptions());

        assertEquals(3, client.attempts);
    }

    @Test
    void anyOtherFailureIsThrownAtOnce() throws Exception {
        MqttException refused = new MqttException(new IOException("Connection refused"));
        ScriptedClient client = new ScriptedClient(List.of(refused));

        assertSame(refused, assertThrows(MqttException.class,
                () -> PahoWebSocketConnect.connect(client, new MqttConnectOptions())));
        assertEquals(1, client.attempts);
    }

    @Test
    void theRaceIsGivenUpOnAfterFiveAttempts() throws Exception {
        ScriptedClient client = new ScriptedClient(List.of(race(), race(), race(), race(), race(), race()));

        assertThrows(MqttException.class, () -> PahoWebSocketConnect.connect(client, new MqttConnectOptions()));
        assertEquals(5, client.attempts);
    }

    private static MqttException race() {
        return new MqttException(new IOException(RACE));
    }

    /** Fails each connect with the next scripted error, then succeeds. */
    private static final class ScriptedClient extends MqttClient {

        private final Deque<MqttException> failures;
        private int attempts;

        ScriptedClient(List<MqttException> failures) throws MqttException {
            super("ws://127.0.0.1:1/mqtt", "scripted", new MemoryPersistence());
            this.failures = new ArrayDeque<>(failures);
        }

        @Override
        public void connect(MqttConnectOptions options) throws MqttException {
            attempts++;
            MqttException failure = failures.poll();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
