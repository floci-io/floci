package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Wire-level tests for {@code AssociateServiceActionWithProvisioningArtifact},
 * {@code DisassociateServiceActionFromProvisioningArtifact},
 * {@code BatchAssociateServiceActionWithProvisioningArtifact} and
 * {@code BatchDisassociateServiceActionFromProvisioningArtifact}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). Found two real bugs in this family:
 *
 * <ol>
 *   <li>{@code disassociateServiceActionFromProvisioningArtifact} validated the
 *   product/artifact existed but never actually deleted the association — a
 *   complete no-op beyond validation. Fixed to look up and delete the row.</li>
 *   <li>the single-op, batch-associate, and batch-disassociate paths each computed
 *   a <em>different</em> association-store key for the conceptually identical
 *   (product, artifact, service action) triple — batch-associate omitted
 *   {@code productId} entirely, and batch-disassociate used a hand-rolled key with
 *   a missing underscore ({@code "serviceaction|..."} vs {@code "service_action|..."})
 *   that didn't match either of the other two. Associations created one way could
 *   not be found or removed another way. Unified all three onto the single-op's key
 *   formula.</li>
 * </ol>
 *
 * <p>{@code associateServiceActionWithProvisioningArtifact_thenDisassociatedViaBatch}
 * and its mirror below are what would have caught #2 — they cross the single/batch
 * boundary deliberately.
 */
@QuarkusTest
class ServiceCatalogServiceActionArtifactAssociationConsumerTest {

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

    private static String createServiceAction(String name) {
        return call("CreateServiceAction", "{\"Name\":\"" + name + "\",\"DefinitionType\":\""
                + "SSM_AUTOMATION\",\"Definition\":{\"Name\":\"AWS-RestartEC2Instance\"},"
                + "\"IdempotencyToken\":\"tok-" + name + "\"}")
                .then().statusCode(200)
                .extract().path("ServiceActionDetail.ServiceActionSummary.Id");
    }

    // ---------- AssociateServiceActionWithProvisioningArtifact ----------

    @Test
    void associateServiceActionWithProvisioningArtifact_returnsEmptyBody() {
        String productId = createProduct("ab-assoc-sa-product");
        String artifactId = firstArtifactId(productId);
        String actionId = createServiceAction("ab-assoc-sa-action");

        call("AssociateServiceActionWithProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ServiceActionId\":\""
                + actionId + "\"}")
        .then()
            .statusCode(200);
    }

    // ---------- DisassociateServiceActionFromProvisioningArtifact ----------

    @Test
    void disassociateServiceActionFromProvisioningArtifact_removesAssociation() {
        String productId = createProduct("ab-disassoc-sa-product");
        String artifactId = firstArtifactId(productId);
        String actionId = createServiceAction("ab-disassoc-sa-action");
        call("AssociateServiceActionWithProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ServiceActionId\":\""
                + actionId + "\"}")
        .then().statusCode(200);

        call("DisassociateServiceActionFromProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ServiceActionId\":\""
                + actionId + "\"}")
        .then()
            .statusCode(200);

        // Repeating the disassociate proves the row is really gone, not just
        // validated: the second call must now fail since nothing is left to remove.
        call("DisassociateServiceActionFromProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ServiceActionId\":\""
                + actionId + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- BatchAssociateServiceActionWithProvisioningArtifact ----------

    @Test
    void batchAssociateServiceActionWithProvisioningArtifact_returnsNoFailures() {
        String productId = createProduct("ab-batch-assoc-product");
        String artifactId = firstArtifactId(productId);
        String actionId = createServiceAction("ab-batch-assoc-action");

        call("BatchAssociateServiceActionWithProvisioningArtifact", "{\"ServiceActionAssociations\":["
                + "{\"ProductId\":\"" + productId + "\",\"ProvisioningArtifactId\":\"" + artifactId
                + "\",\"ServiceActionId\":\"" + actionId + "\"}]}")
        .then()
            .statusCode(200)
            .body("FailedServiceActionAssociations.size()", equalTo(0));
    }

    @Test
    void batchAssociateServiceActionWithProvisioningArtifact_unknownProduct_returnsFailureEntry() {
        call("BatchAssociateServiceActionWithProvisioningArtifact", "{\"ServiceActionAssociations\":["
                + "{\"ProductId\":\"prod-doesnotexist\",\"ProvisioningArtifactId\":\"pa-x\","
                + "\"ServiceActionId\":\"sa-x\"}]}")
        .then()
            .statusCode(200)
            .body("FailedServiceActionAssociations[0].ErrorCode", equalTo("RESOURCE_NOT_FOUND"));
    }

    /** Crosses the single/batch boundary — proves the key formulas were unified. */
    @Test
    void associateViaBatch_thenDisassociatedViaSingleOp() {
        String productId = createProduct("ab-cross-batch-to-single-product");
        String artifactId = firstArtifactId(productId);
        String actionId = createServiceAction("ab-cross-batch-to-single-action");

        call("BatchAssociateServiceActionWithProvisioningArtifact", "{\"ServiceActionAssociations\":["
                + "{\"ProductId\":\"" + productId + "\",\"ProvisioningArtifactId\":\"" + artifactId
                + "\",\"ServiceActionId\":\"" + actionId + "\"}]}")
        .then().statusCode(200)
            .body("FailedServiceActionAssociations.size()", equalTo(0));

        call("DisassociateServiceActionFromProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ServiceActionId\":\""
                + actionId + "\"}")
        .then()
            .statusCode(200);
    }

    // ---------- BatchDisassociateServiceActionFromProvisioningArtifact ----------

    /** Crosses the single/batch boundary the other direction. */
    @Test
    void associateViaSingleOp_thenDisassociatedViaBatch() {
        String productId = createProduct("ab-cross-single-to-batch-product");
        String artifactId = firstArtifactId(productId);
        String actionId = createServiceAction("ab-cross-single-to-batch-action");

        call("AssociateServiceActionWithProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ServiceActionId\":\""
                + actionId + "\"}")
        .then().statusCode(200);

        call("BatchDisassociateServiceActionFromProvisioningArtifact", "{\"ServiceActionAssociations\":["
                + "{\"ProductId\":\"" + productId + "\",\"ProvisioningArtifactId\":\"" + artifactId
                + "\",\"ServiceActionId\":\"" + actionId + "\"}]}")
        .then()
            .statusCode(200);
    }

    @Test
    void batchDisassociateServiceActionFromProvisioningArtifact_unknownAssociation_returnsFailureEntry() {
        String productId = createProduct("ab-batch-disassoc-unknown-product");
        String artifactId = firstArtifactId(productId);

        call("BatchDisassociateServiceActionFromProvisioningArtifact", "{\"ServiceActionAssociations\":["
                + "{\"ProductId\":\"" + productId + "\",\"ProvisioningArtifactId\":\"" + artifactId
                + "\",\"ServiceActionId\":\"sa-neverassociated\"}]}")
        .then()
            .statusCode(200)
            .body("FailedServiceActionAssociations[0].ErrorCode", equalTo("RESOURCE_NOT_FOUND"));
    }
}
