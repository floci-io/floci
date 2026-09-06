package io.github.hectorvent.floci.services.pipes;

/** A Kafka REST Proxy (Karapace) response outside the expected 2xx status for its call. */
class PipesKafkaRestClientException extends RuntimeException {

    private final int statusCode;

    PipesKafkaRestClientException(String method, String path, int statusCode, String body) {
        super("Kafka REST Proxy " + method + " " + path + " failed: HTTP " + statusCode
                + (body == null || body.isBlank() ? "" : " " + body));
        this.statusCode = statusCode;
    }

    int getStatusCode() {
        return statusCode;
    }
}
