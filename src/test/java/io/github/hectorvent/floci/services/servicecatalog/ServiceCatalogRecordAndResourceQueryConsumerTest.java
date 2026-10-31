package io.github.hectorvent.floci.services.servicecatalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.core.storage.AccountAwareStorageBackend;
import io.github.hectorvent.floci.core.storage.StorageFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-level tests for {@code DescribeRecord}, {@code ListRecordHistory},
 * {@code ListResourcesForTagOption}, {@code ListStackInstancesForProvisionedProduct}
 * and {@code ListServiceActionsForProvisioningArtifact}.
 *
 * <p>Record tests use the AWS JSON wire path so the returned IDs can be checked
 * against both history and {@code DescribeRecord}.
 */
@QuarkusTest
class ServiceCatalogRecordAndResourceQueryConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicecatalog/aws4_request";

    @Inject
    StorageFactory storageFactory;

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
    void listRecordHistory_includesEachRecordForProvisionedProduct() {
        String productId = createProduct("ab-record-history-product");
        String artifactId = firstArtifactId(productId);
        Response imported = call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ProvisionedProductName\":\""
                + "ab-record-history-pp\",\"PhysicalId\":\"phys-ab-record-history\","
                + "\"IdempotencyToken\":\"tok-ab-record-history\"}")
                .then().statusCode(200).extract().response();
        String provisionedId = imported.path("RecordDetail.ProvisionedProductId");
        String importedRecordId = imported.path("RecordDetail.RecordId");
        String updatedRecordId = call("UpdateProvisionedProduct", "{\"ProvisionedProductId\":\""
                + provisionedId + "\",\"UpdateToken\":\"tok-ab-record-history-update\"}")
                .then().statusCode(200).extract().path("RecordDetail.RecordId");

        call("ListRecordHistory", "{}")
        .then()
            .statusCode(200)
            .body("RecordDetails.findAll { it.ProvisionedProductId == '" + provisionedId
                    + "' }.RecordId", containsInAnyOrder(importedRecordId, updatedRecordId))
            .body("RecordDetails.findAll { it.ProvisionedProductId == '" + provisionedId
                    + "' }.RecordType", containsInAnyOrder("IMPORT", "UPDATE_PROVISIONED_PRODUCT"))
            .body("RecordDetails.find { it.RecordId == '" + importedRecordId + "' }.Type", nullValue());

        call("DescribeRecord", "{\"Id\":\"" + importedRecordId + "\"}")
                .then().statusCode(200).body("RecordDetail.RecordId", equalTo(importedRecordId));
        call("DescribeRecord", "{\"Id\":\"" + updatedRecordId + "\"}")
                .then().statusCode(200).body("RecordDetail.RecordId", equalTo(updatedRecordId));
    }

    @Test
    void provisionProductRecordIsStableAndListed() {
        String productId = createProduct("ab-provision-history-product");
        String artifactId = firstArtifactId(productId);
        String request = "{\"ProductId\":\"" + productId + "\",\"ProvisioningArtifactId\":\"" + artifactId
                + "\",\"ProvisionedProductName\":\"ab-provision-history-pp\"}";
        String recordId = call("ProvisionProduct", request)
                .then().statusCode(200).extract().path("RecordDetail.RecordId");
        call("ProvisionProduct", request)
                .then().statusCode(200).body("RecordDetail.RecordId", equalTo(recordId));
        call("ListRecordHistory", "{}")
                .then().statusCode(200)
                .body("RecordDetails.findAll { it.RecordId == '" + recordId + "' }.size()", equalTo(1))
                .body("RecordDetails.find { it.RecordId == '" + recordId + "' }.RecordType",
                        equalTo("PROVISION_PRODUCT"));
        call("DescribeRecord", "{\"Id\":\"" + recordId + "\"}")
                .then().statusCode(200).body("RecordDetail.RecordId", equalTo(recordId));
    }

    @Test
    void legacyProductWithOnlyUpdateRecordRetainsSyntheticCreationHistory() {
        String productId = createProduct("ab-legacy-history-product");
        String artifactId = firstArtifactId(productId);
        Response imported = call("ImportAsProvisionedProduct", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ProvisionedProductName\":\""
                + "ab-legacy-history-pp\",\"PhysicalId\":\"phys-ab-legacy-history\","
                + "\"IdempotencyToken\":\"tok-ab-legacy-history\"}")
                .then().statusCode(200).extract().response();
        String provisionedId = imported.path("RecordDetail.ProvisionedProductId");
        String importedRecordId = imported.path("RecordDetail.RecordId");
        AccountAwareStorageBackend<ObjectNode> records = storageFactory.create("servicecatalog",
                "servicecatalog-associations.json", new TypeReference<Map<String, ObjectNode>>() {});
        assertEquals(1, records.deleteAllAccountsMatching(
                record -> importedRecordId.equals(record.path("RecordId").asText())));

        String updatedRecordId = call("UpdateProvisionedProduct", "{\"ProvisionedProductId\":\""
                + provisionedId + "\",\"UpdateToken\":\"tok-ab-legacy-history-update\"}")
                .then().statusCode(200).extract().path("RecordDetail.RecordId");
        call("ListRecordHistory", "{}")
                .then().statusCode(200)
                .body("RecordDetails.findAll { it.ProvisionedProductId == '" + provisionedId
                        + "' }.size()", equalTo(2))
                .body("RecordDetails.find { it.ProvisionedProductId == '" + provisionedId
                        + "' && !it.RecordId }.Status", equalTo("SUCCEEDED"))
                .body("RecordDetails.find { it.RecordId == '" + updatedRecordId + "' }.RecordType",
                        equalTo("UPDATE_PROVISIONED_PRODUCT"));
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
            .body("StackInstances.size()", Matchers.greaterThan(0));
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
