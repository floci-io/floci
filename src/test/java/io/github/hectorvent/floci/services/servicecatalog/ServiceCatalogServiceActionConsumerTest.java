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
 * Wire-level tests for {@code CreateServiceAction}, {@code UpdateServiceAction},
 * {@code DeleteServiceAction} and {@code ListServiceActions}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). These tests exercise the full create-update-delete-list
 * round trip deliberately: {@code createServiceAction} originally stored its record
 * under key {@code "service_action|" + id} while {@code deleteServiceAction} and
 * {@code updateServiceAction} looked it up / re-stored it under the raw {@code id} —
 * a key mismatch that made {@code DeleteServiceAction} always fail with
 * {@code ResourceNotFoundException} on a freshly-created action, and made
 * {@code UpdateServiceAction} silently duplicate the record instead of overwriting
 * it. {@code listServiceActions} was also a hardcoded-empty stub despite the other
 * three operations being fully storage-backed. All three were fixed together;
 * {@code listServiceActions_afterUpdate_hasNoDuplicates} is what would have caught
 * the duplication specifically.
 */
@QuarkusTest
class ServiceCatalogServiceActionConsumerTest {

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

    private static String createServiceAction(String name) {
        return call("CreateServiceAction", "{\"Name\":\"" + name + "\",\"DefinitionType\":\""
                + "SSM_AUTOMATION\",\"Definition\":{\"Name\":\"AWS-RestartEC2Instance\"},"
                + "\"IdempotencyToken\":\"tok-" + name + "\"}")
                .then().statusCode(200)
                .extract().path("ServiceActionDetail.ServiceActionSummary.Id");
    }

    // ---------- CreateServiceAction ----------

    @Test
    void createServiceAction_returnsSummaryMatchingRequest() {
        call("CreateServiceAction", "{\"Name\":\"ab-create-action\",\"DefinitionType\":\""
                + "SSM_AUTOMATION\",\"Definition\":{\"Name\":\"AWS-RestartEC2Instance\"},"
                + "\"IdempotencyToken\":\"tok-create\"}")
        .then()
            .statusCode(200)
            .body("ServiceActionDetail.ServiceActionSummary.Name", equalTo("ab-create-action"))
            .body("ServiceActionDetail.ServiceActionSummary.DefinitionType", equalTo("SSM_AUTOMATION"))
            .body("ServiceActionDetail.ServiceActionSummary.Id", org.hamcrest.Matchers.notNullValue());
    }

    /**
     * ServiceActionDefinitionType has exactly one member, SSM_AUTOMATION. Any other value
     * was stored verbatim and handed back by DescribeServiceAction as a real action type.
     */
    @Test
    void createServiceAction_unknownDefinitionType_returnsInvalidParameters() {
        call("CreateServiceAction", "{\"Name\":\"ab-action-badtype\",\"DefinitionType\":\""
                + "LAMBDA\",\"Definition\":{\"Name\":\"AWS-RestartEC2Instance\"},"
                + "\"IdempotencyToken\":\"tok-badtype\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"))
            .body("message", org.hamcrest.Matchers.startsWith("DefinitionType must be one of"));
    }

    // ---------- DeleteServiceAction ----------

    @Test
    void deleteServiceAction_removesFreshlyCreatedAction() {
        String id = createServiceAction("ab-delete-action");

        call("DeleteServiceAction", "{\"Id\":\"" + id + "\"}")
        .then()
            .statusCode(200);

        call("ListServiceActions", "{}")
        .then()
            .statusCode(200)
            .body("ServiceActionSummaries.Id", org.hamcrest.Matchers.not(hasItem(id)));
    }

    @Test
    void deleteServiceAction_unknownId_returnsResourceNotFound() {
        call("DeleteServiceAction", "{\"Id\":\"sa-doesnotexist\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceNotFoundException"));
    }

    // ---------- UpdateServiceAction ----------

    @Test
    void updateServiceAction_changesName() {
        String id = createServiceAction("ab-update-action-before");

        call("UpdateServiceAction", "{\"Id\":\"" + id + "\",\"Name\":\"ab-update-action-after\"}")
        .then()
            .statusCode(200)
            .body("ServiceActionDetail.ServiceActionSummary.Id", equalTo(id))
            .body("ServiceActionDetail.ServiceActionSummary.Name", equalTo("ab-update-action-after"));
    }

    @Test
    void listServiceActions_afterUpdate_hasNoDuplicates() {
        String id = createServiceAction("ab-update-nodupe");
        call("UpdateServiceAction", "{\"Id\":\"" + id + "\",\"Name\":\"ab-update-nodupe-2\"}")
        .then().statusCode(200);

        Response list = call("ListServiceActions", "{}");
        list.then().statusCode(200);
        java.util.List<String> ids = list.jsonPath().getList("ServiceActionSummaries.Id", String.class);
        long matches = ids.stream().filter(id::equals).count();
        org.junit.jupiter.api.Assertions.assertEquals(1L, matches,
                "expected exactly one entry for " + id + " after update, found " + matches);
    }

    /**
     * {@code associateServiceActionWithProvisioningArtifact} stores its association
     * row with the same {@code Type: "SERVICE_ACTION"} discriminator that service
     * action records themselves use, distinguished only by which fields are present
     * (association rows have no {@code Id} field). {@code listServiceActions()} must
     * filter those out, not just match on {@code Type}.
     */
    @Test
    void listServiceActions_ignoresProvisioningArtifactAssociationRows() {
        String productId = call("CreateProduct", "{\"Name\":\"ab-action-artifact-product\","
                + "\"Owner\":\"floci-test\",\"ProvisioningArtifactParameters\":[{\"Name\":\"v1\"}]}")
                .then().statusCode(200)
                .extract().path("ProductViewDetail.ProductViewSummary.Id");
        String artifactId = call("ListProvisioningArtifacts", "{\"ProductId\":\"" + productId + "\"}")
                .then().statusCode(200)
                .extract().path("ProvisioningArtifactDetails[0].Id");
        String actionId = createServiceAction("ab-action-for-artifact-assoc");

        call("AssociateServiceActionWithProvisioningArtifact", "{\"ProductId\":\"" + productId
                + "\",\"ProvisioningArtifactId\":\"" + artifactId + "\",\"ServiceActionId\":\""
                + actionId + "\"}")
        .then().statusCode(200);

        Response list = call("ListServiceActions", "{}");
        list.then().statusCode(200);
        java.util.List<String> ids = list.jsonPath().getList("ServiceActionSummaries.Id", String.class);
        org.junit.jupiter.api.Assertions.assertFalse(ids.contains(""),
                "association row leaked into ListServiceActions with an empty Id: " + ids);
        org.junit.jupiter.api.Assertions.assertTrue(ids.contains(actionId),
                "the real service action should still be listed: " + ids);
    }

    // ---------- ListServiceActions ----------

    @Test
    void listServiceActions_includesCreatedAction() {
        String id = createServiceAction("ab-list-action");

        call("ListServiceActions", "{}")
        .then()
            .statusCode(200)
            .body("ServiceActionSummaries.Id", hasItem(id));
    }
}
