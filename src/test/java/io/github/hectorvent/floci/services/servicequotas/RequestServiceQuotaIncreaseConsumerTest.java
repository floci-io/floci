package io.github.hectorvent.floci.services.servicequotas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesRegex;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Wire-level tests for {@code ServiceQuotasV20190624.RequestServiceQuotaIncrease}.
 *
 * <p>These drive the real HTTP route with the real {@code X-Amz-Target} header, so the
 * dispatch case itself is under test: remove the case from
 * {@link ServiceQuotasJsonHandler} and every test here fails on
 * {@code UnknownOperationException}. A green run therefore proves the operation is
 * reachable by name, which a service-level test could not (CS-001).
 *
 * <p><strong>Known limitation asserted here deliberately:</strong> the emulator does not
 * persist increase requests. {@code GetRequestedServiceQuotaChange} and
 * {@code ListRequestedServiceQuotaChangeHistory} are unsupported, so a request is
 * observable only in the response that creates it. {@code Status} is therefore always
 * {@code PENDING} and never advances. Documented in {@code docs/services/servicequotas.md}
 * per CS-021.
 */
@QuarkusTest
class RequestServiceQuotaIncreaseConsumerTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicequotas/aws4_request";
    private static final String TARGET = "ServiceQuotasV20190624.RequestServiceQuotaIncrease";
    private static final String CONCURRENT_BUILDS_QUOTA = "L-2DC20C30";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static io.restassured.response.Response request(String body) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET)
                .header("Authorization", AUTH_HEADER)
                .body(body)
            .when()
                .post("/");
    }

    @Test
    void requestIncrease_curatedQuota_returnsFullyPopulatedPendingRequest() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA
                + "\",\"DesiredValue\":9000}")
        .then()
            .statusCode(200)
            .body("RequestedQuota.Id", matchesRegex("[0-9A-F]{8}"))
            .body("RequestedQuota.ServiceCode", equalTo("codebuild"))
            .body("RequestedQuota.ServiceName", equalTo("AWS CodeBuild"))
            .body("RequestedQuota.QuotaCode", equalTo(CONCURRENT_BUILDS_QUOTA))
            .body("RequestedQuota.QuotaName", equalTo("Concurrently running builds"))
            .body("RequestedQuota.QuotaArn", equalTo(
                    "arn:aws:servicequotas:us-east-1:000000000000:codebuild/" + CONCURRENT_BUILDS_QUOTA))
            .body("RequestedQuota.DesiredValue", equalTo(9000.0f))
            .body("RequestedQuota.Status", equalTo("PENDING"))
            .body("RequestedQuota.Unit", equalTo("None"))
            .body("RequestedQuota.GlobalQuota", equalTo(false))
            .body("RequestedQuota.QuotaRequestedAtLevel", equalTo("ACCOUNT"))
            .body("RequestedQuota.Requester", notNullValue())
            .body("RequestedQuota.Created", notNullValue())
            .body("RequestedQuota.LastUpdated", notNullValue());
    }

    @Test
    void requestIncrease_generatedQuotaOnUnknownService_resolves() {
        String quotaCode = ServiceQuotasService.syntheticQuotaCode("widgetfactory", "Resources per Region");
        request("{\"ServiceCode\":\"widgetfactory\",\"QuotaCode\":\"" + quotaCode
                + "\",\"DesiredValue\":1}")
        .then()
            .statusCode(200)
            .body("RequestedQuota.QuotaCode", equalTo(quotaCode))
            .body("RequestedQuota.QuotaName", equalTo("Resources per Region"))
            .body("RequestedQuota.ServiceCode", equalTo("widgetfactory"));
    }

    /** The id must be stable so a caller can correlate a repeated request. */
    @Test
    void requestIncrease_sameQuotaTwice_returnsSameId() {
        String body = "{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-B99A9384\",\"DesiredValue\":2000}";
        String first = request(body).then().statusCode(200)
                .extract().path("RequestedQuota.Id");
        String second = request(body).then().statusCode(200)
                .extract().path("RequestedQuota.Id");
        assertEquals(first, second);
    }

    @Test
    void requestIncrease_withContextId_emitsQuotaContext() {
        request("{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-B99A9384\",\"DesiredValue\":10,"
                + "\"ContextId\":\"arn:aws:lambda:us-east-1:000000000000:function:fn\"}")
        .then()
            .statusCode(200)
            .body("RequestedQuota.QuotaContext.ContextId",
                    equalTo("arn:aws:lambda:us-east-1:000000000000:function:fn"))
            .body("RequestedQuota.QuotaContext.ContextScope", equalTo("RESOURCE"));
    }

    /** An unmodelled-by-the-emulator field must be absent, not null-valued or invented. */
    @Test
    void requestIncrease_withoutContextId_omitsQuotaContextAndCaseId() {
        request("{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-B99A9384\",\"DesiredValue\":10}")
        .then()
            .statusCode(200)
            .body("RequestedQuota.QuotaContext", nullValue())
            .body("RequestedQuota.CaseId", nullValue());
    }

    @Test
    void requestIncrease_unknownQuotaCode_returnsNoSuchResource() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"L-DEADBEEF\",\"DesiredValue\":1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void requestIncrease_missingQuotaCode_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"codebuild\",\"DesiredValue\":1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void requestIncrease_missingServiceCode_returnsIllegalArgument() {
        request("{\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA + "\",\"DesiredValue\":1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void requestIncrease_missingDesiredValue_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA + "\"}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void requestIncrease_negativeDesiredValue_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA
                + "\",\"DesiredValue\":-1}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    /** Packet bounds are min=0 max=10000000000; one past the top must be rejected. */
    @Test
    void requestIncrease_desiredValueAboveModelledMaximum_returnsIllegalArgument() {
        request("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA
                + "\",\"DesiredValue\":10000000001}")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }
}
