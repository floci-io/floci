package io.floci.aws.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/** With {@code floci.services.s3.region-redirect} off, a bucket is served to a request signed for any Region. */
@QuarkusTest
@TestProfile(S3RegionRedirectDisabledIntegrationTest.RegionRedirectOff.class)
class S3RegionRedirectDisabledIntegrationTest {

    private static final String US_EAST_1_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260325/us-east-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=test";
    private static final String EU_WEST_1_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260325/eu-west-1/s3/aws4_request, SignedHeaders=host;x-amz-date, Signature=test";

    public static class RegionRedirectOff implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.s3.region-redirect", "false");
        }
    }

    @Test
    void bucketIsServedToARequestSignedForAnotherRegion() {
        String bucket = "redirect-off-bucket";
        given().header("Authorization", US_EAST_1_AUTH).when().put("/" + bucket).then().statusCode(200);
        try {
            given().header("Authorization", EU_WEST_1_AUTH)
            .when()
                .get("/" + bucket)
            .then()
                .statusCode(200)
                .body(containsString("<Name>" + bucket + "</Name>"));

            given().header("Authorization", EU_WEST_1_AUTH)
            .when()
                .head("/" + bucket)
            .then()
                .statusCode(200)
                .header("x-amz-bucket-region", equalTo("us-east-1"));
        } finally {
            given().header("Authorization", US_EAST_1_AUTH).when().delete("/" + bucket).then().statusCode(204);
        }
    }
}
