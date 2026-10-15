package io.github.hectorvent.floci.testutil;

import io.restassured.RestAssured;
import io.restassured.response.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;

public final class StsRequests {

    private StsRequests() {}

    public record Credentials(String key, String secret, String token) {}

    public static Response request(Credentials caller, String service, String action, Map<String, String> params)
            throws Exception {
        StringBuilder body = new StringBuilder("Action=").append(action);
        params.forEach((name, value) -> body.append('&').append(name).append('=')
                .append(URLEncoder.encode(value, StandardCharsets.UTF_8)));
        URI endpoint = URI.create("http://localhost:" + RestAssured.port + "/");
        Map<String, String> extra = caller.token() == null ? Map.of()
                : Map.of("x-amz-security-token", caller.token());
        Map<String, String> headers = new LinkedHashMap<>(extra);
        headers.putAll(AwsRequestSigner.signedAs(caller.key(), caller.secret(), service)
                .headersFor("POST", endpoint, extra, body.toString().getBytes(StandardCharsets.UTF_8)));
        return given().headers(headers).contentType("application/x-www-form-urlencoded")
                .body(body.toString()).post(endpoint);
    }
}
