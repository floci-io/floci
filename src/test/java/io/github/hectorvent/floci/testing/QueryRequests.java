package io.github.hectorvent.floci.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

public final class QueryRequests {
    private QueryRequests() {}

    public static RequestSpecification request(String account, String service, String action, String... params) {
        RequestSpecification request = given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261022/us-east-1/" + service + "/aws4_request, SignedHeaders=host, Signature=abc")
                .formParam("Action", action);
        for (int i = 0; i < params.length; i += 2) {
            request = request.formParam(params[i], params[i + 1]);
        }
        return request;
    }
}
