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
 * Wire-level tests for {@code CreateProvisionedProductPlan},
 * {@code DescribeProvisionedProductPlan}, {@code ExecuteProvisionedProductPlan},
 * {@code DeleteProvisionedProductPlan} and {@code ListProvisionedProductPlans}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). This family had no persistence at all before this session's
 * fix: {@code createProvisionedProductPlan} built and returned a plan object without
 * ever storing it, so every other operation in the family failed on a freshly
 * created plan — {@code describeProvisionedProductPlan} and
 * {@code deleteProvisionedProductPlan} always 404'd, and
 * {@code listProvisionedProductPlans} scanned the wrong store entirely
 * (provisioned products, not plans). Persistence was added via
 * {@code associationStore} with a new {@code Type: "PROVISIONED_PRODUCT_PLAN"}
 * discriminator, per this project's established convention for relationships with
 * no dedicated store. Three of the five handler methods for this family each
 * expected different field names on the same conceptual object ({@code PlanId} vs
 * {@code Id}, {@code PlanName} vs {@code Name}) — the stored plan object carries
 * both names for every dual-named field so each handler's existing (untouched)
 * response-building code keeps working.
 */
@QuarkusTest
class ServiceCatalogProvisionedProductPlanConsumerTest {

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

    private static String createPlan(String productId, String artifactId, String planName) {
        return call("CreateProvisionedProductPlan", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"PlanName\":\"" + planName
                + "\",\"PlanType\":\"CLOUDFORMATION\",\"ProvisionedProductName\":\"" + planName
                + "-pp\",\"IdempotencyToken\":\"tok-" + planName + "\"}")
                .then().statusCode(200)
                .extract().path("PlanId");
    }

    // ---------- CreateProvisionedProductPlan ----------

    /**
     * ProvisionedProductPlanType has exactly one member, CLOUDFORMATION. The plan was
     * created for any PlanType string and then described as CLOUDFORMATION regardless,
     * so a caller asking for a plan type AWS does not offer got a fake success.
     */
    @Test
    void createProvisionedProductPlan_unknownPlanType_returnsInvalidParameters() {
        String productId = createProduct("ab-plan-badtype");
        String artifactId = firstArtifactId(productId);

        call("CreateProvisionedProductPlan", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId
                + "\",\"PlanName\":\"ab-plan-badtype\",\"PlanType\":\"TERRAFORM\","
                + "\"ProvisionedProductName\":\"ab-plan-badtype-pp\","
                + "\"IdempotencyToken\":\"tok-ab-plan-badtype\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"))
            .body("message", org.hamcrest.Matchers.startsWith("PlanType must be one of"));
    }

    @Test
    void createProvisionedProductPlan_returnsPlanMatchingRequest() {
        String productId = createProduct("ab-plan-create-product");
        String artifactId = firstArtifactId(productId);

        call("CreateProvisionedProductPlan", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"PlanName\":\"ab-create-plan\","
                + "\"PlanType\":\"CLOUDFORMATION\",\"ProvisionedProductName\":\"ab-create-plan-pp\","
                + "\"IdempotencyToken\":\"tok-create-plan\"}")
        .then()
            .statusCode(200)
            .body("PlanName", equalTo("ab-create-plan"))
            .body("ProvisionProductId", equalTo(productId))
            .body("ProvisioningArtifactId", equalTo(artifactId))
            .body("PlanId", org.hamcrest.Matchers.notNullValue());
    }

    // ---------- DescribeProvisionedProductPlan ----------

    @Test
    void describeProvisionedProductPlan_returnsCreatedPlan() {
        String productId = createProduct("ab-plan-describe-product");
        String artifactId = firstArtifactId(productId);
        String planId = createPlan(productId, artifactId, "ab-describe-plan");

        call("DescribeProvisionedProductPlan", "{\"PlanId\":\"" + planId + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductPlanDetails.PlanId", equalTo(planId))
            .body("ProvisionedProductPlanDetails.PlanName", equalTo("ab-describe-plan"))
            .body("ProvisionedProductPlanDetails.ProvisionProductId", equalTo(productId))
            .body("ProvisionedProductPlanDetails.ProvisionProductName", equalTo("ab-describe-plan-pp"))
            .body("ProvisionedProductPlanDetails.ProductId", equalTo(productId))
            .body("ProvisionedProductPlanDetails.ProvisioningArtifactId", equalTo(artifactId));
    }

    @Test
    void describeProvisionedProductPlan_unknownId_returnsResourceNotFound() {
        call("DescribeProvisionedProductPlan", "{\"PlanId\":\"plan-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- ExecuteProvisionedProductPlan ----------

    @Test
    void executeProvisionedProductPlan_returnsRecordDetail() {
        String productId = createProduct("ab-plan-execute-product");
        String artifactId = firstArtifactId(productId);
        String planId = createPlan(productId, artifactId, "ab-execute-plan");

        call("ExecuteProvisionedProductPlan", "{\"PlanId\":\"" + planId
                + "\",\"IdempotencyToken\":\"tok-execute-plan\"}")
        .then()
            .statusCode(200)
            .body("RecordDetail.Status", equalTo("SUCCEEDED"));
    }

    @Test
    void executeProvisionedProductPlan_unknownId_returnsResourceNotFound() {
        call("ExecuteProvisionedProductPlan", "{\"PlanId\":\"plan-doesnotexist\","
                + "\"IdempotencyToken\":\"tok-execute-unknown\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- DeleteProvisionedProductPlan ----------

    @Test
    void deleteProvisionedProductPlan_removesPlan() {
        String productId = createProduct("ab-plan-delete-product");
        String artifactId = firstArtifactId(productId);
        String planId = createPlan(productId, artifactId, "ab-delete-plan");

        call("DeleteProvisionedProductPlan", "{\"PlanId\":\"" + planId + "\"}")
        .then()
            .statusCode(200);

        call("DescribeProvisionedProductPlan", "{\"PlanId\":\"" + planId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    @Test
    void deleteProvisionedProductPlan_unknownId_returnsResourceNotFound() {
        call("DeleteProvisionedProductPlan", "{\"PlanId\":\"plan-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- ListProvisionedProductPlans ----------

    @Test
    void listProvisionedProductPlans_scopedToProvisionProductId_returnsOnlyMatchingPlans() {
        String productA = createProduct("ab-plan-list-product-a");
        String artifactA = firstArtifactId(productA);
        String planA = createPlan(productA, artifactA, "ab-list-plan-a");

        String productB = createProduct("ab-plan-list-product-b");
        String artifactB = firstArtifactId(productB);
        createPlan(productB, artifactB, "ab-list-plan-b");

        call("ListProvisionedProductPlans", "{\"ProvisionProductId\":\"" + productA + "\"}")
        .then()
            .statusCode(200)
            .body("ProvisionedProductPlans.PlanId", hasItem(planA))
            .body("ProvisionedProductPlans[0].PlanName", equalTo("ab-list-plan-a"))
            .body("ProvisionedProductPlans[0].ProvisionProductId", equalTo(productA))
            .body("ProvisionedProductPlans[0].ProvisionProductName", equalTo("ab-list-plan-a-pp"))
            .body("ProvisionedProductPlans.size()", equalTo(1));
    }
}
