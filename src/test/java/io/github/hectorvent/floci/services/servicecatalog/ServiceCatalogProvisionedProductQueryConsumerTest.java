package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * Wire-level tests for {@code ImportAsProvisionedProduct},
 * {@code GetProvisionedProductOutputs} and {@code ScanProvisionedProducts}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001).
 */
@QuarkusTest
class ServiceCatalogProvisionedProductQueryConsumerTest {

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

    // ---------- ImportAsProvisionedProduct ----------

    @Test
    void importAsProvisionedProduct_returnsRecordMatchingRequest() {
        String productId = createProduct("ab-import-pp-product");
        String artifactId = firstArtifactId(productId);

        call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ProvisionedProductName\":\""
                + "ab-import-pp\",\"PhysicalId\":\"phys-ab-import-pp\",\"IdempotencyToken\":\""
                + "tok-ab-import-pp\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.ProvisionedProductName", equalTo("ab-import-pp"))
            .body("RecordDetail.ProductId", equalTo(productId))
            .body("RecordDetail.Status", equalTo("SUCCEEDED"))
            .body("RecordDetail.RecordType", equalTo("IMPORT"));
    }

    @Test
    void importAsProvisionedProduct_unknownProduct_returnsResourceNotFound() {
        call("ImportAsProvisionedProduct", "{\"ProductId\":\"prod-doesnotexist\","
                + "\"ProvisioningArtifactId\":\"pa-anything\",\"ProvisionedProductName\":\"ab-x\","
                + "\"PhysicalId\":\"phys-ab-x\",\"IdempotencyToken\":\"tok-ab-x\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- GetProvisionedProductOutputs ----------

    @Test
    void getProvisionedProductOutputs_knownProduct_returnsEmptyOutputs() {
        String productId = createProduct("ab-outputs-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-outputs-pp");

        call("GetProvisionedProductOutputs", "{\"ProvisionedProductId\":\"" + provisionedId + "\"}")
        .then()
            .statusCode(200)
            .body("Outputs.size()", equalTo(0));
    }

    @Test
    void getProvisionedProductOutputs_unknownProduct_returnsResourceNotFound() {
        call("GetProvisionedProductOutputs", "{\"ProvisionedProductId\":\"pp-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void getProvisionedProductOutputs_missingIdAndName_returnsInvalidParameters() {
        call("GetProvisionedProductOutputs", "{}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    // ---------- ScanProvisionedProducts ----------

    @Test
    void scanProvisionedProducts_includesImportedProduct() {
        String productId = createProduct("ab-scan-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-scan-pp");

        call("ScanProvisionedProducts", "{}")
        .then()
            .statusCode(200)
            .body("ProvisionedProducts.Id", hasItem(provisionedId));
    }
}
