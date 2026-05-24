package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3RequestPaymentIntegrationTest {

    private static final String BUCKET = "request-payment-int-test";
    private static final String REQUESTER_PAYS_XML = """
            <RequestPaymentConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Payer>Requester</Payer>
            </RequestPaymentConfiguration>
            """;
    private static final String BUCKET_OWNER_PAYS_XML = """
            <RequestPaymentConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Payer>BucketOwner</Payer>
            </RequestPaymentConfiguration>
            """;

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(2)
    void getRequestPaymentBeforePutReturnsBucketOwnerDefault() {
        given()
        .when()
            .get("/" + BUCKET + "?requestPayment")
        .then()
            .statusCode(200)
            .body(containsString("<RequestPaymentConfiguration"))
            .body(containsString("<Payer>BucketOwner</Payer>"));
    }

    @Test
    @Order(3)
    void putRequestPaymentReturns200() {
        given()
            .body(REQUESTER_PAYS_XML)
        .when()
            .put("/" + BUCKET + "?requestPayment")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void getRequestPaymentReturnsStoredPayer() {
        given()
        .when()
            .get("/" + BUCKET + "?requestPayment")
        .then()
            .statusCode(200)
            .body(containsString("<Payer>Requester</Payer>"));
    }

    /**
     * Regression test for the Terraform-breaking bug where {@code PUT /{bucket}?requestPayment}
     * fell through to the bucket-creation handler and returned {@code 409 BucketAlreadyOwnedByYou}.
     * Real S3 allows {@code PutBucketRequestPayment} to be called repeatedly on an existing
     * bucket — and Terraform's {@code aws_s3_bucket_request_payment_configuration} resource
     * relies on this idempotency.
     */
    @Test
    @Order(5)
    void putRequestPaymentIsIdempotentOnExistingBucket() {
        given()
            .body(BUCKET_OWNER_PAYS_XML)
        .when()
            .put("/" + BUCKET + "?requestPayment")
        .then()
            .statusCode(200)
            .body(not(containsString("BucketAlreadyOwnedByYou")));
    }

    @Test
    @Order(6)
    void putRequestPaymentRejectsInvalidPayer() {
        given()
            .body("""
                    <RequestPaymentConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Payer>SomeoneElse</Payer>
                    </RequestPaymentConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?requestPayment")
        .then()
            .statusCode(400)
            .body(containsString("MalformedXML"));
    }

    @Test
    @Order(7)
    void putRequestPaymentOnMissingBucketReturns404() {
        given()
            .body(BUCKET_OWNER_PAYS_XML)
        .when()
            .put("/this-bucket-does-not-exist-rp?requestPayment")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }
}
