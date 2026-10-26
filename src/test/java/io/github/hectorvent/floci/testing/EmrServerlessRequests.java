package io.github.hectorvent.floci.testing;

import io.restassured.specification.RequestSpecification;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;

/** One EMR Serverless REST JSON request, signed for a named Region. */
public final class EmrServerlessRequests {
    private EmrServerlessRequests() {}

    public static RequestSpecification emrServerless(String region) {
        return given()
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=TEST/20260819/" + region
                        + "/emr-serverless/aws4_request, SignedHeaders=host;x-amz-date, Signature=test")
                .contentType("application/json");
    }

    /**
     * The tagging path for an ARN, sent as the SDK sends it: one path segment with ':' and '/'
     * percent-encoded. Send it with {@code urlEncodingEnabled(false)}, or it is encoded twice.
     */
    public static String tagsPath(String arn) {
        return "/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);
    }
}
