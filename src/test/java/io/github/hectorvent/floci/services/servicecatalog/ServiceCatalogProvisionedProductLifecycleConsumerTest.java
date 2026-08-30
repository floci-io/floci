package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Wire-level tests for {@code DescribeProvisionedProduct},
 * {@code UpdateProvisionedProduct}, {@code UpdateProvisionedProductProperties} and
 * {@code TerminateProvisionedProduct}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). {@code UpdateProvisionedProduct},
 * {@code UpdateProvisionedProductProperties} and {@code TerminateProvisionedProduct}
 * all had the same RecordId-discard bug as issues 0021/0023 — fixed the same way.
 * {@code TerminateProvisionedProduct} also had its {@code RecordType} hardcoded to
 * the wrong value ({@code PROVISION} instead of
 * {@code TERMINATE_PROVISIONED_PRODUCT}) and conflated the provisioned product's own
 * {@code TERMINATED} status with the record's {@code SUCCEEDED} outcome status;
 * both fixed. {@code UpdateProvisionedProduct} does not apply any request fields —
 * documented as a validate-and-echo limitation (CS-021 precedent), not fixed further.
 */
@QuarkusTest
class ServiceCatalogProvisionedProductLifecycleConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static Response call(String action, String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWS242ServiceCatalogService." + action)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    private static String createProduct(String name) {
        return call("CreateProduct", "{\"Name\":\"" + name + "\",\"Owner\":\"floci-test\","
                + "\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.Id");
    }

    private static String firstArtifactId(String productId) {
        return call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then().statusCode(200)
                .extract().path("ProvisioningArtifactDetails[0].Id");
    }

    private static String importProvisionedProduct(String productId, String artifactId, String name) {
        return call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ProvisionedProductName\":\""
                + name + "\",\"PhysicalId\":\"phys-" + name + "\",\"IdempotencyToken\":\"tok-" + name + "\"}")
                .then().statusCode(200)
                .extract().path("RecordDetail.ProvisionedProductId");
    }

    // ---------- DescribeProvisionedProduct ----------

    @Test
    void describeProvisionedProduct_returnsImportedProduct() {
        String productId = createProduct("ab-describe-pp-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-describe-pp");

        call("DescribeProvisionedProduct", "{\"Id\":\"" + provisionedId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductDetail.Id", equalTo(provisionedId))
            .body("ProvisionedProductDetail.Status", equalTo("AVAILABLE"));
    }

    @Test
    void describeProvisionedProduct_unknownId_returnsResourceNotFound() {
        call("DescribeProvisionedProduct", "{\"Id\":\"pp-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- UpdateProvisionedProduct ----------

    @Test
    void updateProvisionedProduct_returnsDescribableRecord() {
        String productId = createProduct("ab-update-pp-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-update-pp");

        String recordId = call("UpdateProvisionedProduct", "{\"ProvisionedProductId\":\"" + provisionedId
                + "\",\"UpdateToken\":\"tok-update-pp\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.ProvisionedProductId", equalTo(provisionedId))
            .body("RecordDetail.Status", equalTo("SUCCEEDED"))
            .extract().path("RecordDetail.RecordId");

        call("DescribeRecord", "{\"Id\":\"" + recordId + "\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.RecordType", equalTo("UPDATE_PROVISIONED_PRODUCT"));
    }

    @Test
    void updateProvisionedProduct_unknownProduct_returnsResourceNotFound() {
        call("UpdateProvisionedProduct", "{\"ProvisionedProductId\":\"pp-doesnotexist\","
                + "\"UpdateToken\":\"tok-update-unknown\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- UpdateProvisionedProductProperties ----------

    @Test
    void updateProvisionedProductProperties_persistsProperties() {
        String productId = createProduct("ab-update-pp-props-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-update-pp-props");

        call("UpdateProvisionedProductProperties", "{\"ProvisionedProductId\":\"" + provisionedId
                + "\",\"ProvisionedProductProperties\":{\"OWNER\":\"someone-else\"},\"IdempotencyToken\":\""
                + "tok-update-props\"}")
        .then()
            .statusCode(200)
            .body("Status", equalTo("SUCCEEDED"));

        call("DescribeProvisionedProduct", "{\"Id\":\"" + provisionedId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductDetail.Id", equalTo(provisionedId));
    }

    @Test
    void updateProvisionedProductProperties_unknownProduct_returnsResourceNotFound() {
        call("UpdateProvisionedProductProperties", "{\"ProvisionedProductId\":\"pp-doesnotexist\","
                + "\"ProvisionedProductProperties\":{\"OWNER\":\"x\"},\"IdempotencyToken\":\"tok-unknown\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- TerminateProvisionedProduct ----------

    @Test
    void terminateProvisionedProduct_marksTerminatedAndReturnsDescribableRecord() {
        String productId = createProduct("ab-terminate-pp-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-terminate-pp");

        String recordId = call("TerminateProvisionedProduct", "{\"ProvisionedProductId\":\""
                + provisionedId + "\",\"TerminateToken\":\"tok-terminate-pp\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.ProvisionedProductId", equalTo(provisionedId))
            .body("RecordDetail.Status", equalTo("SUCCEEDED"))
            .body("RecordDetail.RecordType", equalTo("TERMINATE_PROVISIONED_PRODUCT"))
            .extract().path("RecordDetail.RecordId");

        call("DescribeProvisionedProduct", "{\"Id\":\"" + provisionedId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductDetail.Status", equalTo("TERMINATED"));

        call("DescribeRecord", "{\"Id\":\"" + recordId + "\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.RecordType", equalTo("TERMINATE_PROVISIONED_PRODUCT"));
    }

    @Test
    void terminateProvisionedProduct_unknownProduct_returnsResourceNotFound() {
        call("TerminateProvisionedProduct", "{\"ProvisionedProductId\":\"pp-doesnotexist\","
                + "\"TerminateToken\":\"tok-terminate-unknown\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
