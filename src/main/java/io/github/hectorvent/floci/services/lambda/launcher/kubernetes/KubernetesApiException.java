package io.github.hectorvent.floci.services.lambda.launcher.kubernetes;

/** A Kubernetes API server response outside 2xx (and, for a get, other than a plain 404). */
public class KubernetesApiException extends RuntimeException {

    private final int statusCode;

    public KubernetesApiException(String method, String path, int statusCode, String body) {
        super("Kubernetes API " + method + " " + path + " failed: HTTP " + statusCode
                + (body == null || body.isBlank() ? "" : " " + body));
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}
