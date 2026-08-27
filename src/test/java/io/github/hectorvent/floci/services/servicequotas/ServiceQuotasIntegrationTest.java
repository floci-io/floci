package io.github.hectorvent.floci.services.servicequotas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Integration tests for the Service Quotas service.
 * Protocol: JSON 1.1 — Content-Type: application/x-amz-json-1.1,
 * X-Amz-Target: ServiceQuotasV20190624.&lt;Action&gt;
 */
@QuarkusTest
class ServiceQuotasIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=AKID/20260101/us-east-1/servicequotas/aws4_request";
    private static final String CONCURRENT_BUILDS_QUOTA = "L-2DC20C30";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void listServiceQuotas_codebuild_includesConcurrentlyRunningBuilds() {
        String quotaPath = "Quotas.find { it.QuotaCode == '" + CONCURRENT_BUILDS_QUOTA + "' }";
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body(quotaPath + ".QuotaName", equalTo("Concurrently running builds"))
            .body(quotaPath + ".ServiceCode", equalTo("codebuild"))
            .body(quotaPath + ".ServiceName", equalTo("AWS CodeBuild"))
            .body(quotaPath + ".QuotaArn", equalTo(
                    "arn:aws:servicequotas:us-east-1:000000000000:codebuild/" + CONCURRENT_BUILDS_QUOTA))
            .body(quotaPath + ".Value", greaterThanOrEqualTo(60.0f))
            .body(quotaPath + ".Unit", equalTo("None"))
            .body(quotaPath + ".Adjustable", equalTo(true))
            .body(quotaPath + ".GlobalQuota", equalTo(false))
            .body(quotaPath + ".QuotaAppliedAtLevel", equalTo("ACCOUNT"));
    }

    @Test
    void listServiceQuotas_unknownServiceCode_returnsGeneratedQuotas() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"widgetfactory\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quotas.size()", equalTo(3))
            .body("Quotas[0].ServiceCode", equalTo("widgetfactory"))
            .body("Quotas[0].QuotaCode", startsWith("L-"))
            .body("Quotas[0].Value", equalTo(5000.0f))
            .body("Quotas[0].Unit", equalTo("None"));
    }

    @Test
    void listServiceQuotas_missingServiceCode_returnsIllegalArgument() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void listServiceQuotas_paginatesDeterministically() {
        Response first = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"codebuild\",\"MaxResults\":1}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Quotas.size()", equalTo(1))
                .body("NextToken", notNullValue())
                .extract().response();
        String firstCode = first.path("Quotas[0].QuotaCode");
        String nextToken = first.path("NextToken");
        assertEquals(CONCURRENT_BUILDS_QUOTA, firstCode);
        assertNotNull(nextToken);

        Response second = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"codebuild\",\"NextToken\":\"" + nextToken + "\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .body("Quotas.size()", equalTo(3))
                .extract().response();
        List<String> remainingCodes = second.path("Quotas.QuotaCode");
        assertEquals(3, remainingCodes.size());
        assertNull(second.path("NextToken"));
    }

    @Test
    void listServiceQuotas_invalidNextToken_returnsInvalidPaginationToken() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\",\"NextToken\":\"not_a_token!\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidPaginationTokenException"));
    }

    @Test
    void getServiceQuota_returnsConcurrentlyRunningBuilds() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quota.QuotaCode", equalTo(CONCURRENT_BUILDS_QUOTA))
            .body("Quota.QuotaName", equalTo("Concurrently running builds"))
            .body("Quota.Value", greaterThanOrEqualTo(60.0f))
            .body("Quota.Adjustable", equalTo(true))
            .body("Quota.GlobalQuota", equalTo(false));
    }

    @Test
    void getServiceQuota_unknownQuotaCode_returnsNoSuchResource() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"L-DOESNOTEX\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void getAwsDefaultServiceQuota_matchesAppliedQuota() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetAWSDefaultServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"" + CONCURRENT_BUILDS_QUOTA + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quota.QuotaCode", equalTo(CONCURRENT_BUILDS_QUOTA))
            .body("Quota.QuotaName", equalTo("Concurrently running builds"));
    }

    @Test
    void listAwsDefaultServiceQuotas_matchesAppliedQuotas() {
        Response applied = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListServiceQuotas")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"lambda\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().response();

        Response defaults = given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.ListAWSDefaultServiceQuotas")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"lambda\"}")
                .when()
                .post("/")
                .then()
                .statusCode(200)
                .extract().response();

        List<String> appliedCodes = applied.path("Quotas.QuotaCode");
        List<String> defaultCodes = defaults.path("Quotas.QuotaCode");
        assertEquals(appliedCodes, defaultCodes);
        assertEquals("L-B99A9384", appliedCodes.getFirst());
    }

    @Test
    void unknownAction_returnsUnknownOperation() {
        // Deliberately a name AWS will never define. This test previously used
        // RequestServiceQuotaIncrease, which then became supported and silently
        // inverted the test's premise. A synthetic name keeps the assertion honest
        // no matter which real operations get implemented later.
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ThisOperationDoesNotExist")
            .header("Authorization", AUTH_HEADER)
            .body("{}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("UnknownOperationException"));
    }
}
