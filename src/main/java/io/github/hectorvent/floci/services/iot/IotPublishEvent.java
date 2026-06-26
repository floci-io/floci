package io.github.hectorvent.floci.services.iot;

public record IotPublishEvent(String topic, byte[] payload) {
}
