package io.floci.aws.core.common;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

/**
 * The services whose REST routes another controller also claims (DLM, EMR Serverless, GuardDuty
 * Malware Protection plans and Resource Explorer 2) are reached through {@link SignedServiceRoute}: by the
 * signing service, from the header or the query string, and never by calling the internal prefix
 * the routes are registered under.
 */
@QuarkusTest
class SignedServiceRouteIntegrationTest {

    private static final String ACCOUNT = "000000000301";
    private static final String S3_AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20260215/us-east-1/s3/aws4_request";

    @ParameterizedTest
    @ValueSource(strings = {"/_guardduty/malware-protection-plan", "/_dlm/policies", "/_re2/CreateIndex",
            "/_emrserverless/applications"})
    void aRequestStraightToAnInternalPrefixIsRefusedAsAnInvalidBucket(String path) {
        given().header("Authorization", S3_AUTH)
                .when().get(path)
                .then().statusCode(400)
                .body(containsString("<Code>InvalidBucketName</Code>"));
        given().when().get(path)
                .then().statusCode(400)
                .body(containsString("<Code>InvalidBucketName</Code>"));
    }

    @Test
    void aBucketNamedAfterTheOldResourceExplorerPrefixIsAnOrdinaryBucket() {
        given().header("Authorization", S3_AUTH)
                .when().put("/re2")
                .then().statusCode(200);
        given().header("Authorization", S3_AUTH)
                .body("not an index")
                .when().put("/re2/CreateIndex")
                .then().statusCode(200);
        given().header("Authorization", S3_AUTH)
                .when().get("/re2/CreateIndex")
                .then().statusCode(200)
                .body(equalTo("not an index"));
    }

    @Test
    void aQuerySignedRequestIsRoutedByItsCredential() {
        given().queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .queryParam("X-Amz-Credential", ACCOUNT + "/20260215/us-east-1/dlm/aws4_request")
                .when().get("/policies")
                .then().statusCode(200)
                .body("Policies", notNullValue());
        given().queryParam("X-Amz-Algorithm", "AWS4-HMAC-SHA256")
                .queryParam("X-Amz-Credential", ACCOUNT + "/20260215/us-east-1/guardduty/aws4_request")
                .when().get("/malware-protection-plan")
                .then().statusCode(200)
                .body("malwareProtectionPlans", notNullValue());
    }
}
