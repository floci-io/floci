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
 * Wire-level tests for {@code DescribeRecord}, {@code ListRecordHistory},
 * {@code ListResourcesForTagOption}, {@code ListStackInstancesForProvisionedProduct}
 * and {@code ListServiceActionsForProvisioningArtifact}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). {@code describeRecord} looked up records via a
 * {@code Type: "RECORD"} association row that nothing ever wrote — every
 * record-producing operation generated a throwaway random {@code RecordId} in the
 * handler layer and discarded it, so {@code DescribeRecord} 404'd unconditionally.
 * Fixed for {@code ImportAsProvisionedProduct}'s path (the one this session already
 * covers): the service now persists a {@code RECORD} row and returns its id, and the
 * handler uses that id instead of generating its own. Other record-producing
 * operations (e.g. {@code ExecuteProvisionedProductServiceAction},
 * {@code TerminateProvisionedProduct}) still generate unpersisted record ids and
 * remain undescribable — documented as a limitation, not fixed here (out of this
 * op-family's scope; same fix pattern applies when those ops are tested).
 */
@QuarkusTest
class ServiceCatalogRecordAndResourceQueryConsumerTest {

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

    // ---------- DescribeRecord ----------

    @Test
    void describeRecord_returnsRecordForImportedProduct() {
        String productId = createProduct("ab-record-describe-product");
        String artifactId = firstArtifactId(productId);
        String recordId = call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ProvisionedProductName\":\""
                + "ab-record-describe-pp\",\"PhysicalId\":\"phys-ab-record-describe\","
                + "\"IdempotencyToken\":\"tok-ab-record-describe\"}")
                .then().statusCode(200)
                .extract().path("RecordDetail.RecordId");

        call("DescribeRecord", "{\"Id\":\"" + recordId + "\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.RecordId", equalTo(recordId))
            .body("RecordDetail.ProductId", equalTo(productId))
            .body("RecordDetail.RecordType", equalTo("IMPORT"))
            .body("RecordDetail.Status", equalTo("SUCCEEDED"));
    }

    @Test
    void describeRecord_unknownId_returnsResourceNotFound() {
        call("DescribeRecord", "{\"Id\":\"rec-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- ListRecordHistory ----------

    @Test
    void listRecordHistory_includesImportedProduct() {
        String productId = createProduct("ab-record-history-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ProvisionedProductName\":\""
                + "ab-record-history-pp\",\"PhysicalId\":\"phys-ab-record-history\","
                + "\"IdempotencyToken\":\"tok-ab-record-history\"}")
                .then().statusCode(200)
                .extract().path("RecordDetail.ProvisionedProductId");

        call("ListRecordHistory", "{}")
        .then()
            .statusCode(200)
            .body("RecordDetails.ProvisionedProductId", hasItem(provisionedId));
    }

    // ---------- ListResourcesForTagOption ----------

    @Test
    void listResourcesForTagOption_returnsAssociatedProduct() {
        String productId = createProduct("ab-tagoption-product");
        String tagOptionId = call("CreateTagOption", "{\"Key\":\"env\",\"Value\":\"prod\"}")
                .then().statusCode(200)
                .extract().path("TagOptionDetail.Id");
        call("AssociateTagOptionWithResource", "{\"ResourceId\":\"" + productId
                + "\",\"TagOptionId\":\"" + tagOptionId + "\"}")
        .then().statusCode(200);

        call("ListResourcesForTagOption", "{\"TagOptionId\":\"" + tagOptionId + "\"}")
        .then()
            .statusCode(200)
            .body("ResourceDetails.Id", hasItem(productId));
    }

    @Test
    void listResourcesForTagOption_unknownTagOption_returnsResourceNotFound() {
        call("ListResourcesForTagOption", "{\"TagOptionId\":\"tag-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- ListStackInstancesForProvisionedProduct ----------

    @Test
    void listStackInstancesForProvisionedProduct_returnsInstanceForImportedProduct() {
        String productId = createProduct("ab-stackinstances-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ProvisionedProductName\":\""
                + "ab-stackinstances-pp\",\"PhysicalId\":\"phys-ab-stackinstances\","
                + "\"IdempotencyToken\":\"tok-ab-stackinstances\"}")
                .then().statusCode(200)
                .extract().path("RecordDetail.ProvisionedProductId");

        call("ListStackInstancesForProvisionedProduct", "{\"ProvisionedProductId\":\""
                + provisionedId + "\"}")
        .then()
            .statusCode(200)
            .body("StackInstances.size()", org.hamcrest.Matchers.greaterThan(0));
    }

    @Test
    void listStackInstancesForProvisionedProduct_unknownProduct_returnsResourceNotFound() {
        call("ListStackInstancesForProvisionedProduct", "{\"ProvisionedProductId\":\"pp-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- ListServiceActionsForProvisioningArtifact ----------

    @Test
    void listServiceActionsForProvisioningArtifact_knownArtifact_returnsEmptyList() {
        String productId = createProduct("ab-safpa-product");
        String artifactId = firstArtifactId(productId);

        call("ListServiceActionsForProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\"}")
        .then()
            .statusCode(200)
            .body("ServiceActionSummaries.size()", equalTo(0));
    }

    @Test
    void listServiceActionsForProvisioningArtifact_unknownArtifact_returnsResourceNotFound() {
        String productId = createProduct("ab-safpa-unknown-product");

        call("ListServiceActionsForProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"pa-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }
}
