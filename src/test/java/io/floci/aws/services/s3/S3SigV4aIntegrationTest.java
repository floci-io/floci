package io.floci.aws.services.s3;

import io.floci.aws.testing.ValidateSignaturesProfile;
import io.floci.aws.testutil.S3SigV4aRequestSigner;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.RestAssured;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * With {@code floci.auth.validate-signatures} on, an S3 request signed with SigV4A
 * ({@code AWS4-ECDSA-P256-SHA256}) is served when its signature verifies and refused, with S3's
 * own error codes, when it does not, in both the {@code Authorization} header and a presigned URL.
 */
@QuarkusTest
@TestProfile(ValidateSignaturesProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3SigV4aIntegrationTest {

    private static final String BUCKET = "sigv4a-bucket";
    private static final String KEY = "object.txt";
    private static final String PATH = "/" + BUCKET + "/" + KEY;
    private static final S3SigV4aRequestSigner SIGNER = S3SigV4aRequestSigner.signedAs("test", "test");

    private static URI uri(String path) {
        return URI.create("http://localhost:" + RestAssured.port + path);
    }

    @Test
    @Order(1)
    void headerSignedRequestsForAllRegionsAreServed() {
        given().filter(SIGNER).when().put("/" + BUCKET).then().statusCode(200);
        given().filter(SIGNER).body("sigv4a body").when().put(PATH).then().statusCode(200);
        given().filter(SIGNER).when().get(PATH).then().statusCode(200).body(equalTo("sigv4a body"));
    }

    @Test
    @Order(2)
    void headerSignedRequestForOneRegionIsServed() {
        given().filter(SIGNER.forRegions("us-east-1")).when().get(PATH).then()
                .statusCode(200).body(equalTo("sigv4a body"));
    }

    @Test
    @Order(3)
    void headerSignedRequestForAListOfRegionsIsServed() {
        given().filter(SIGNER.forRegions("us-east-1,eu-west-1")).when().get(PATH).then()
                .statusCode(200).body(equalTo("sigv4a body"));
    }

    @Test
    @Order(4)
    void headerSignedQueryParametersAreCovered() {
        given().filter(SIGNER).when().get("/" + BUCKET + "?list-type=2&prefix=obj").then()
                .statusCode(200).body("ListBucketResult.KeyCount", equalTo("1"));
    }

    @Test
    @Order(5)
    void headerSignatureThatDoesNotVerifyIsRejected() {
        given().filter(SIGNER.withSignature("3006020101020101")).when().get(PATH).then()
                .statusCode(403).body("Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    @Order(6)
    void headerSignedWithAnotherSecretIsRejected() {
        given().filter(S3SigV4aRequestSigner.signedAs("test", "not-the-secret")).when().get(PATH).then()
                .statusCode(403).body("Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    @Order(7)
    void headerSignedWithAnUnknownKeyIsRejected() {
        given().filter(S3SigV4aRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret")).when().get(PATH).then()
                .statusCode(403).body("Error.Code", equalTo("InvalidAccessKeyId"));
    }

    @Test
    @Order(8)
    void headerWithoutASignedRegionSetIsMalformed() {
        given().filter(SIGNER.withoutSignedRegionSet()).when().get(PATH).then()
                .statusCode(400).body("Error.Code", equalTo("AuthorizationHeaderMalformed"));
    }

    @Test
    @Order(9)
    void headerNamingAnUnknownRegionIsMalformed() {
        given().filter(SIGNER.forRegions("mars-1")).when().get(PATH).then()
                .statusCode(400).body("Error.Code", equalTo("AuthorizationHeaderMalformed"));
    }

    @Test
    @Order(10)
    void presignedUrlForAllRegionsIsServed() throws Exception {
        given().urlEncodingEnabled(false).when()
                .get(PATH + "?" + SIGNER.presignedQuery("GET", uri(PATH), 300))
                .then().statusCode(200).body(equalTo("sigv4a body"));
    }

    @Test
    @Order(11)
    void presignedUrlForOneRegionIsServed() throws Exception {
        given().urlEncodingEnabled(false).when()
                .get(PATH + "?" + SIGNER.forRegions("us-east-1").presignedQuery("GET", uri(PATH), 300))
                .then().statusCode(200).body(equalTo("sigv4a body"));
    }

    @Test
    @Order(12)
    void presignedUrlSignedForAnotherMethodIsRejected() throws Exception {
        given().urlEncodingEnabled(false).when()
                .get(PATH + "?" + SIGNER.presignedQuery("PUT", uri(PATH), 300))
                .then().statusCode(403).body("Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    @Order(13)
    void presignedUrlSignedWithAnotherSecretIsRejected() throws Exception {
        S3SigV4aRequestSigner forged = S3SigV4aRequestSigner.signedAs("test", "not-the-secret");
        given().urlEncodingEnabled(false).when()
                .get(PATH + "?" + forged.presignedQuery("GET", uri(PATH), 300))
                .then().statusCode(403).body("Error.Code", equalTo("SignatureDoesNotMatch"));
    }

    @Test
    @Order(14)
    void presignedUrlWithAnUnknownKeyIsRejected() throws Exception {
        S3SigV4aRequestSigner unknown = S3SigV4aRequestSigner.signedAs("AKIAUNKNOWNACCESSKEY", "some-secret");
        given().urlEncodingEnabled(false).when()
                .get(PATH + "?" + unknown.presignedQuery("GET", uri(PATH), 300))
                .then().statusCode(403).body("Error.Code", equalTo("InvalidAccessKeyId"));
    }

    @Test
    @Order(15)
    void presignedUrlWithoutARegionSetIsRejected() throws Exception {
        String query = SIGNER.presignedQuery("GET", uri(PATH), 300).replaceAll("&X-Amz-Region-Set=[^&]*", "");
        given().urlEncodingEnabled(false).when().get(PATH + "?" + query).then()
                .statusCode(400).body("Error.Code", equalTo("AuthorizationQueryParametersError"));
    }

    @Test
    @Order(16)
    void headerWithDuplicateCredentialIsRejected() {
        given()
            .header("Authorization", "AWS4-ECDSA-P256-SHA256 Credential=test/20260101/s3/aws4_request, "
                    + "SignedHeaders=host;x-amz-date;x-amz-region-set, Signature=3006020101020101, "
                    + "Credential=test2/20260101/s3/aws4_request")
            .header("X-Amz-Region-Set", "*")
            .header("x-amz-date", "20260101T000000Z")
        .when().get(PATH).then()
            .statusCode(400).body("Error.Code", equalTo("AuthorizationHeaderMalformed"));
    }
}
