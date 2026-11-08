package io.floci.aws.services.iot;

public record IotPublishEvent(String topic, byte[] payload) {
}
