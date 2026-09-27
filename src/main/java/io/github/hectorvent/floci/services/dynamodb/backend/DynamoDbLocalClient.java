package io.github.hectorvent.floci.services.dynamodb.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.auth.SigV4RequestValidator;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Api;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Reply;
import io.github.hectorvent.floci.services.dynamodb.backend.DynamoDbOperations.Scope;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Forwards DynamoDB and DynamoDB Streams wire calls to an external DynamoDB Local over SigV4-signed
 * HTTP. The caller's account becomes the access key and its region the signing region, so Local
 * keeps each account and region apart. Requests are never retried.
 */
class DynamoDbLocalClient implements AutoCloseable {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String SIGNED_HEADERS = "content-type;host;x-amz-date;x-amz-target";
    private static final String SERVICE = "dynamodb";
    private static final String SECRET_KEY = "floci";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private final URI endpoint;
    private final Duration requestTimeout;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String host;
    private final String path;

    DynamoDbLocalClient(URI endpoint, Duration connectTimeout, Duration requestTimeout, ObjectMapper mapper) {
        this.endpoint = endpoint;
        this.requestTimeout = requestTimeout;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();
        this.host = endpoint.getHost() + (endpoint.getPort() != -1 ? ":" + endpoint.getPort() : "");
        String rawPath = endpoint.getRawPath();
        this.path = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
    }

    Reply send(Scope scope, Api api, String action, JsonNode body) throws IOException, InterruptedException {
        return send(scope, api, action, body, requestTimeout);
    }

    Reply send(Scope scope, Api api, String action, JsonNode body, Duration budget)
            throws IOException, InterruptedException {
        byte[] payload = mapper.writeValueAsBytes(body == null ? mapper.createObjectNode() : body);
        String target = (api == Api.DYNAMODB ? "DynamoDB_20120810." : "DynamoDBStreams_20120810.") + action;
        String amzDate = AMZ_DATE.format(ZonedDateTime.now(ZoneOffset.UTC));
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
                .timeout(budget.compareTo(requestTimeout) < 0 ? budget : requestTimeout)
                .header("Content-Type", CONTENT_TYPE)
                .header("X-Amz-Date", amzDate)
                .header("X-Amz-Target", target)
                .header("Authorization", authorization(scope, target, amzDate, payload))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        byte[] responseBody = response.body();
        JsonNode replyBody = responseBody == null || responseBody.length == 0 ? null : mapper.readTree(responseBody);
        return new Reply(response.statusCode(), replyBody, Map.of());
    }

    URI endpoint() {
        return endpoint;
    }

    @Override
    public void close() {
        http.shutdownNow();
    }

    private String authorization(Scope scope, String target, String amzDate, byte[] payload) {
        String date = amzDate.substring(0, 8);
        String credentialScope = date + "/" + scope.region() + "/" + SERVICE + "/aws4_request";
        try {
            String canonical = "POST\n" + path + "\n\n"
                    + "content-type:" + CONTENT_TYPE + "\n"
                    + "host:" + host + "\n"
                    + "x-amz-date:" + amzDate + "\n"
                    + "x-amz-target:" + target + "\n\n"
                    + SIGNED_HEADERS + "\n"
                    + SigV4RequestValidator.sha256Hex(payload);
            String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + credentialScope + "\n"
                    + SigV4RequestValidator.sha256Hex(canonical);
            byte[] signingKey = SigV4RequestValidator.deriveSigningKey(SECRET_KEY, date, scope.region(), SERVICE);
            String signature = SigV4RequestValidator.hexEncode(SigV4RequestValidator.hmacSha256(signingKey, toSign));
            return "AWS4-HMAC-SHA256 Credential=floci" + scope.accountId() + "/" + credentialScope
                    + ", SignedHeaders=" + SIGNED_HEADERS + ", Signature=" + signature;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign DynamoDB Local request", e);
        }
    }
}
