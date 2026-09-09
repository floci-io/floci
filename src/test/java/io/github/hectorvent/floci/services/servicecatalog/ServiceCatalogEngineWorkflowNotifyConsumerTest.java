package io.github.hectorvent.floci.services.servicecatalog;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * Wire-level tests for {@code NotifyProvisionProductEngineWorkflowResult},
 * {@code NotifyUpdateProvisionedProductEngineWorkflowResult} and
 * {@code NotifyTerminateProvisionedProductEngineWorkflowResult}.
 *
 * <p>New class, not appended to an existing one, so falsifiability isolates per
 * operation (CS-001). {@code NotifyTerminate...} already validated {@code Status}
 * against {@code SUCCEEDED}/{@code FAILED}; the other two accepted any string. Made
 * consistent across all three as part of this session's batch (they represent the
 * same concept — an external provisioning engine reporting a workflow outcome).
 */
@QuarkusTest
class ServiceCatalogEngineWorkflowNotifyConsumerTest {

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

    // ---------- NotifyProvisionProductEngineWorkflowResult ----------

    @Test
    void notifyProvisionProductEngineWorkflowResult_succeeded_returnsEmptyBody() {
        call("NotifyProvisionProductEngineWorkflowResult", "{\"WorkflowToken\":\"wf-1\","
                + "\"RecordId\":\"rec-1\",\"Status\":\"SUCCEEDED\",\"IdempotencyToken\":\"tok-1\"}")
        .then()
            .statusCode(200);
    }

    @Test
    void notifyProvisionProductEngineWorkflowResult_invalidStatus_returnsInvalidParameters() {
        call("NotifyProvisionProductEngineWorkflowResult", "{\"WorkflowToken\":\"wf-2\","
                + "\"RecordId\":\"rec-2\",\"Status\":\"BOGUS\",\"IdempotencyToken\":\"tok-2\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    // ---------- NotifyUpdateProvisionedProductEngineWorkflowResult ----------

    @Test
    void notifyUpdateProvisionedProductEngineWorkflowResult_failed_returnsEmptyBody() {
        call("NotifyUpdateProvisionedProductEngineWorkflowResult", "{\"WorkflowToken\":\"wf-3\","
                + "\"RecordId\":\"rec-3\",\"Status\":\"FAILED\",\"IdempotencyToken\":\"tok-3\"}")
        .then()
            .statusCode(200);
    }

    @Test
    void notifyUpdateProvisionedProductEngineWorkflowResult_invalidStatus_returnsInvalidParameters() {
        call("NotifyUpdateProvisionedProductEngineWorkflowResult", "{\"WorkflowToken\":\"wf-4\","
                + "\"RecordId\":\"rec-4\",\"Status\":\"BOGUS\",\"IdempotencyToken\":\"tok-4\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }

    // ---------- NotifyTerminateProvisionedProductEngineWorkflowResult ----------

    @Test
    void notifyTerminateProvisionedProductEngineWorkflowResult_succeeded_returnsEmptyBody() {
        call("NotifyTerminateProvisionedProductEngineWorkflowResult", "{\"WorkflowToken\":\"wf-5\","
                + "\"RecordId\":\"rec-5\",\"Status\":\"SUCCEEDED\",\"IdempotencyToken\":\"tok-5\"}")
        .then()
            .statusCode(200);
    }

    @Test
    void notifyTerminateProvisionedProductEngineWorkflowResult_invalidStatus_returnsInvalidParameters() {
        call("NotifyTerminateProvisionedProductEngineWorkflowResult", "{\"WorkflowToken\":\"wf-6\","
                + "\"RecordId\":\"rec-6\",\"Status\":\"BOGUS\",\"IdempotencyToken\":\"tok-6\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidParametersException"));
    }
}
