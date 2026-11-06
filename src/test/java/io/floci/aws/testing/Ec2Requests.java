package io.floci.aws.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

public final class Ec2Requests {
    private Ec2Requests() {}

    public static RequestSpecification ec2(String action, String accountId, String region) {
        return given().header("Authorization",
                        "AWS4-HMAC-SHA256 Credential=" + accountId + "/20261016/" + region + "/ec2/aws4_request")
                .formParam("Action", action);
    }
}
