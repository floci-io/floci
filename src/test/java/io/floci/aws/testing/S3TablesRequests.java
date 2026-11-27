package io.floci.aws.testing;

import io.restassured.specification.RequestSpecification;

import static io.restassured.RestAssured.given;

/** One S3 Tables REST JSON request, signed for s3tables so it reaches S3 Tables rather than S3. */
public final class S3TablesRequests {

    private S3TablesRequests() {}

    public static RequestSpecification s3tables() {
        return given().header("Authorization",
                "AWS4-HMAC-SHA256 Credential=000000000000/20260101/us-east-1/s3tables/aws4_request");
    }
}
