package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Wire-level tests for {@code ExecuteProvisionedProductServiceAction}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). Same bug shape as issue 0021 (DescribeRecord): the handler
 * generated and discarded its own throwaway {@code RecordId} instead of persisting
 * one. Fixed the same way — the service now persists a {@code RECORD} row and
 * returns its id, the handler reads it back.
 */
@QuarkusTest
class ServiceCatalogExecuteServiceActionConsumerTest {

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

    @Test
    void executeProvisionedProductServiceAction_returnsDescribableRecord() {
        String productId = createProduct("ab-execute-action-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-execute-action-pp");

        String recordId = call("ExecuteProvisionedProductServiceAction", "{\"ProvisionedProductId\":\""
                + provisionedId + "\",\"ServiceActionId\":\"sa-anything\",\"ExecuteToken\":\""
                + "tok-execute-action\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.ProvisionedProductId", equalTo(provisionedId))
            .body("RecordDetail.Status", equalTo("SUCCEEDED"))
            .extract().path("RecordDetail.RecordId");

        call("DescribeRecord", "{\"Id\":\"" + recordId + "\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.RecordId", equalTo(recordId))
            .body("RecordDetail.ProvisionedProductId", equalTo(provisionedId))
            .body("RecordDetail.RecordType", equalTo("UPDATE_PROVISIONED_PRODUCT"));
    }

    @Test
    void executeProvisionedProductServiceAction_unknownProduct_returnsResourceNotFound() {
        call("ExecuteProvisionedProductServiceAction", "{\"ProvisionedProductId\":\"pp-doesnotexist\","
                + "\"ServiceActionId\":\"sa-anything\",\"ExecuteToken\":\"tok-unknown\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void executeProvisionedProductServiceAction_missingServiceActionId_returnsInvalidParameters() {
        String productId = createProduct("ab-execute-action-missing-sa-product");
        String artifactId = firstArtifactId(productId);
        String provisionedId = importProvisionedProduct(productId, artifactId, "ab-execute-action-missing-sa");

        call("ExecuteProvisionedProductServiceAction", "{\"ProvisionedProductId\":\"" + provisionedId
                + "\",\"ExecuteToken\":\"tok-missing-sa\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }
}
