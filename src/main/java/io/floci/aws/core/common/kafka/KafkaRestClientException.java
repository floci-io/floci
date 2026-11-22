package io.floci.aws.core.common.kafka;

/** A Kafka REST Proxy (Karapace) response outside the expected 2xx status for its call. */
public class KafkaRestClientException extends RuntimeException {

    private final int statusCode;

    public KafkaRestClientException(String method, String path, int statusCode, String body) {
        super("Kafka REST Proxy " + method + " " + path + " failed: HTTP " + statusCode
                + (body == null || body.isBlank() ? "" : " " + body));
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
