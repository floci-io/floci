package io.github.hectorvent.floci.services.iot;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * With the default {@code auto-start=false} the broker is not listening until something needs
 * it. A WebSocket upgrade on {@code /mqtt} is one of those things: the first one starts the
 * broker, concurrent first ones all get a session, and an upgrade after a stop starts it again.
 */
@QuarkusTest
@TestProfile(IotMqttWebSocketLazyStartIntegrationTest.Profile.class)
class IotMqttWebSocketLazyStartIntegrationTest {

    private static final int PORT = 18838;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int testHttpPort;

    @Inject
    IotMqttBrokerService broker;

    @BeforeEach
    void stopBroker() throws InterruptedException {
        broker.stop();
        awaitPortClosed();
    }

    @Test
    void firstUpgradeStartsTheBroker() throws Exception {
        assertFalse(broker.isRunning(), "precondition: nothing has started the broker");

        MqttClient client = new MqttClient(ws(), "lazy-" + System.nanoTime(), new MemoryPersistence());
        client.connect();
        try {
            assertTrue(client.isConnected());
            assertTrue(broker.isRunning(), "the upgrade started the broker");
            try (Socket tcp = new Socket()) {
                tcp.connect(new InetSocketAddress("127.0.0.1", PORT), 1000);
            }
        } finally {
            client.disconnect();
            client.close();
        }
    }

    @Test
    void concurrentFirstUpgradesAllGetASession() throws Exception {
        int clients = 8;
        List<MqttClient> connected = new CopyOnWriteArrayList<>();
        List<Throwable> failures = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(clients);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < clients; i++) {
            String clientId = "lazy-burst-" + i + "-" + System.nanoTime();
            Thread thread = new Thread(() -> {
                try {
                    MqttClient client = new MqttClient(ws(), clientId, new MemoryPersistence());
                    client.connect();
                    connected.add(client);
                } catch (Exception e) {
                    failures.add(e);
                } finally {
                    done.countDown();
                }
            });
            threads.add(thread);
            thread.start();
        }
        assertTrue(done.await(30, TimeUnit.SECONDS));
        try {
            assertTrue(failures.isEmpty(), "every concurrent first upgrade succeeds: " + failures);
            for (MqttClient client : connected) {
                assertTrue(client.isConnected());
            }
        } finally {
            for (MqttClient client : connected) {
                client.disconnect();
                client.close();
            }
        }
    }

    @Test
    void upgradeAfterAStopStartsTheBrokerAgain() throws Exception {
        MqttClient first = new MqttClient(ws(), "lazy-first-" + System.nanoTime(), new MemoryPersistence());
        CountDownLatch firstLost = new CountDownLatch(1);
        first.setCallback(new MqttCallback() {
            @Override
            public void connectionLost(Throwable cause) {
                firstLost.countDown();
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) {
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {
            }
        });
        first.connect();
        assertTrue(broker.isRunning());

        broker.stop();
        awaitPortClosed();
        assertFalse(broker.isRunning());
        assertTrue(firstLost.await(10, TimeUnit.SECONDS), "stopping the broker drops the WebSocket session");
        first.close();

        MqttClient second = new MqttClient(ws(), "lazy-second-" + System.nanoTime(), new MemoryPersistence());
        second.connect();
        try {
            assertTrue(second.isConnected());
            assertTrue(broker.isRunning(), "the next upgrade started the broker again");
        } finally {
            second.disconnect();
            second.close();
        }
    }

    private String ws() {
        return "ws://127.0.0.1:" + testHttpPort + "/mqtt";
    }

    /** The close future can resolve before the OS releases the port; wait until it refuses. */
    private static void awaitPortClosed() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 100);
            } catch (Exception refused) {
                return;
            }
            Thread.sleep(50);
        }
        assertThrows(Exception.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", PORT), 100);
            }
        }, "the broker port is still accepting after stop()");
    }

    public static final class Profile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "floci.services.iot.mqtt.enabled", "true",
                    "floci.services.iot.mqtt.auto-start", "false",
                    "floci.services.iot.mqtt.host", "127.0.0.1",
                    "floci.services.iot.mqtt.port", Integer.toString(PORT));
        }
    }
}
