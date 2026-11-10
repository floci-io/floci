package io.floci.aws.services.bedrockruntime;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

/**
 * The Converse request the proxy tests send: a SigV4-shaped Authorization header and one user
 * message, to the {@code fixture} model id, which reaches the stand-in through passthrough.
 */
final class ConverseRequests {

    static final String AUTHORIZATION = "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/bedrock/aws4_request";
    static final String BODY = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"synthetic\"}]}]}";

    private ConverseRequests() {
    }

    static HttpRequest to(URI base, String operation) {
        return HttpRequest.newBuilder(base.resolve("/model/fixture/" + operation))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("Authorization", AUTHORIZATION)
                .POST(HttpRequest.BodyPublishers.ofString(BODY))
                .build();
    }
}
