package io.github.hectorvent.floci.services.iot;

import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;

import java.io.IOException;

/**
 * Connects a Paho client over WebSocket, retrying only the handshake failure Paho causes itself.
 * Paho encodes the Sec-WebSocket-Key, and checks the server's Sec-WebSocket-Accept, with one
 * Base64 encoder that every client shares unlocked. Clients that connect at the same time can read
 * each other's value and fail with "Incorrect Sec-WebSocket-Key" before the server is involved.
 * With the default MQTT version, Paho answers that failure by retrying with an older version,
 * which the broker closes, so the race would show up as "Connection lost" instead.
 */
final class PahoWebSocketConnect {

    private static final String HANDSHAKE_RACE = "WebSocket Response header: Incorrect Sec-WebSocket-Key";
    private static final int ATTEMPTS = 5;

    private PahoWebSocketConnect() {
    }

    static void connect(MqttClient client, MqttConnectOptions options) throws MqttException {
        options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        for (int attempt = 1; ; attempt++) {
            try {
                client.connect(options);
                return;
            } catch (MqttException e) {
                if (attempt == ATTEMPTS || !isHandshakeRace(e)) {
                    throw e;
                }
            }
        }
    }

    private static boolean isHandshakeRace(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException && HANDSHAKE_RACE.equals(cause.getMessage())) {
                return true;
            }
        }
        return false;
    }
}
