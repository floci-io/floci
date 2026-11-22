package io.floci.aws.core.common.kafka;

/** The Kafka REST Proxy reported the requested topic does not exist on the broker. */
public class KafkaTopicNotFoundException extends RuntimeException {

    private final String topic;

    public KafkaTopicNotFoundException(String topic, Throwable cause) {
        super("Kafka topic " + topic + " does not exist", cause);
        this.topic = topic;
    }

    public String getTopic() {
        return topic;
    }
}
