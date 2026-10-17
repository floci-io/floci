package io.github.hectorvent.floci.services.servicequotas;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

    @BeforeEach
    void resetState() {
        given().post("/_floci/state/reset").then().statusCode(200);
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
    void organizationsQuotaUsesAwsQuotaCode() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetServiceQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"organizations\",\"QuotaCode\":\"L-E619E033\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("Quota.QuotaCode", equalTo("L-E619E033"))
            .body("Quota.QuotaName", equalTo("Maximum number of accounts"))
            .body("Quota.Value", equalTo(50.0f))
            .body("Quota.GlobalQuota", equalTo(true));
    }

    @ParameterizedTest
    @CsvSource(value = {"us-east-1|111111111111|aws", "cn-north-1|333333333333|aws-cn"}, delimiter = '|')
    void ec2StandardQuotaUsesAwsCodeAndRegionalOwner(String region, String account, String partition) {
        String code = "L-1216C47A";
        String name = "Running On-Demand Standard (A, C, D, H, I, M, R, T, Z) instances";
        String authorization = "AWS4-HMAC-SHA256 Credential=" + account + "/20261015/"
                + region + "/servicequotas/aws4_request";
        for (String action : List.of("GetServiceQuota", "GetAWSDefaultServiceQuota")) {
            given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624." + action)
                .header("Authorization", authorization)
                .body("{\"ServiceCode\":\"ec2\",\"QuotaCode\":\"" + code + "\"}")
            .when().post("/")
            .then().log().ifValidationFails().statusCode(200)
                .body("Quota.QuotaCode", equalTo(code))
                .body("Quota.QuotaName", equalTo(name))
                .body("Quota.Value", equalTo(5.0f))
                .body("Quota.Adjustable", equalTo(true))
                .body("Quota.GlobalQuota", equalTo(false))
                .body("Quota.QuotaAppliedAtLevel", equalTo("ACCOUNT"))
                .body("Quota.QuotaArn", equalTo("arn:" + partition + ":servicequotas:" + region + ":"
                        + account + ":ec2/" + code));
        }
        for (String action : List.of("ListServiceQuotas", "ListAWSDefaultServiceQuotas")) {
            given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624." + action)
                .header("Authorization", authorization)
                .body("{\"ServiceCode\":\"ec2\",\"MaxResults\":100}")
            .when().post("/")
            .then().log().ifValidationFails().statusCode(200)
                .body("Quotas.find { it.QuotaCode == 'L-1216C47A' }.QuotaName", equalTo(name))
                .body("Quotas.find { it.QuotaCode == 'L-1216C47A' }.Value", equalTo(5.0f))
                .body("Quotas.size()", equalTo(4));
        }
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn"})
    void ec2IncreaseRequestsRemainIsolatedByAccount(String region, String partition) {
        String firstAccount = "111111111111";
        String secondAccount = "333333333333";
        String requestBody = "{\"ServiceCode\":\"ec2\",\"QuotaCode\":\"L-1216C47A\",\"DesiredValue\":10}";
        String firstId = quotaRequest("RequestServiceQuotaIncrease", requestBody, region, firstAccount)
                .then().statusCode(200)
                .body("RequestedQuota.QuotaArn", equalTo("arn:" + partition + ":servicequotas:" + region
                        + ":" + firstAccount + ":ec2/L-1216C47A"))
                .extract().path("RequestedQuota.Id");
        String secondId = quotaRequest("RequestServiceQuotaIncrease", requestBody, region, secondAccount)
                .then().statusCode(200)
                .body("RequestedQuota.QuotaArn", equalTo("arn:" + partition + ":servicequotas:" + region
                        + ":" + secondAccount + ":ec2/L-1216C47A"))
                .extract().path("RequestedQuota.Id");
        assertNotEquals(firstId, secondId);
        for (String account : List.of(firstAccount, secondAccount)) {
            String ownId = account.equals(firstAccount) ? firstId : secondId;
            String otherId = account.equals(firstAccount) ? secondId : firstId;
            quotaRequest("RequestServiceQuotaIncrease", requestBody, region, account)
                    .then().statusCode(400).body("__type", equalTo("ResourceAlreadyExistsException"));
            quotaRequest("ListRequestedServiceQuotaChangeHistoryByQuota",
                    "{\"ServiceCode\":\"ec2\",\"QuotaCode\":\"L-1216C47A\"}", region, account)
                    .then().statusCode(200).body("RequestedQuotas.Id", equalTo(List.of(ownId)));
            quotaRequest("GetRequestedServiceQuotaChange", "{\"RequestId\":\"" + ownId + "\"}", region, account)
                    .then().statusCode(200).body("RequestedQuota.Id", equalTo(ownId));
            quotaRequest("GetRequestedServiceQuotaChange", "{\"RequestId\":\"" + otherId + "\"}", region, account)
                    .then().statusCode(400).body("__type", equalTo("NoSuchResourceException"));
        }
    }

    private Response quotaRequest(String action, String body, String region, String account) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624." + action)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + account
                        + "/20261016/" + region + "/servicequotas/aws4_request")
                .body(body).when().post("/");
    }

    @Test
    void vpcQuotasUseAwsQuotaCodesAsDefaultAndApplied() {
        for (String target : new String[] {"GetAWSDefaultServiceQuota", "GetServiceQuota"}) {
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624." + target)
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-FE5A380F\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Quota.QuotaCode", equalTo("L-FE5A380F"))
                .body("Quota.QuotaName", equalTo("NAT gateways per Availability Zone"))
                .body("Quota.Value", equalTo(5.0f));

            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624." + target)
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-2AEEBF1A\"}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .body("Quota.QuotaCode", equalTo("L-2AEEBF1A"))
                .body("Quota.QuotaName", equalTo("Rules per network ACL"))
                .body("Quota.Value", equalTo(20.0f));
        }
    }

    @Test
    void listRequestedQuotaHistoryByQuotaReturnsEmptyHistoryForKnownQuota() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistoryByQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-FE5A380F\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuotas.size()", equalTo(0));
    }

    @Test
    void listRequestedQuotaHistoryByQuotaRejectsImpossibleNextToken() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistoryByQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-FE5A380F\",\"NextToken\":\"MQ\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("InvalidPaginationTokenException"));
    }

    @Test
    void listRequestedQuotaHistoryByQuotaRejectsUnknownQuota() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistoryByQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"organizations\",\"QuotaCode\":\"L-DOESNOTEX\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void requestOrganizationsQuotaIncreasePreservesGlobalQuota() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.RequestServiceQuotaIncrease")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"organizations\",\"QuotaCode\":\"L-E619E033\",\"DesiredValue\":100}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuota.ServiceCode", equalTo("organizations"))
            .body("RequestedQuota.QuotaCode", equalTo("L-E619E033"))
            .body("RequestedQuota.GlobalQuota", equalTo(true));
    }

    @Test
    void getRequestedServiceQuotaChangeReturnsStoredRequest() {
        String requestId =
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.RequestServiceQuotaIncrease")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"codebuild\",\"QuotaCode\":\"L-2DC20C30\",\"DesiredValue\":9000}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("RequestedQuota.Id");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetRequestedServiceQuotaChange")
            .header("Authorization", AUTH_HEADER)
            .body("{\"RequestId\":\"" + requestId + "\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuota.Id", equalTo(requestId))
            .body("RequestedQuota.ServiceCode", equalTo("codebuild"))
            .body("RequestedQuota.QuotaCode", equalTo("L-2DC20C30"))
            .body("RequestedQuota.DesiredValue", notNullValue())
            .body("RequestedQuota.Status", equalTo("PENDING"));
    }

    @Test
    void getRequestedServiceQuotaChange_unknownId_returnsNoSuchResource() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetRequestedServiceQuotaChange")
            .header("Authorization", AUTH_HEADER)
            .body("{\"RequestId\":\"DEADBEEF\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("NoSuchResourceException"));
    }

    @Test
    void getRequestedServiceQuotaChange_emptyRequestId_returnsIllegalArgument() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.GetRequestedServiceQuotaChange")
            .header("Authorization", AUTH_HEADER)
            .body("{\"RequestId\":\"\"}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("IllegalArgumentException"));
    }

    @Test
    void requestedIncreaseAppearsInHistoryForItsQuota() {
        String requestId =
            given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ServiceQuotasV20190624.RequestServiceQuotaIncrease")
                .header("Authorization", AUTH_HEADER)
                .body("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-2AEEBF1A\",\"DesiredValue\":40}")
            .when()
                .post("/")
            .then()
                .statusCode(200)
                .extract()
                .path("RequestedQuota.Id");

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.ListRequestedServiceQuotaChangeHistoryByQuota")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-2AEEBF1A\"}")
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .body("RequestedQuotas.find { it.Id == '" + requestId + "' }.QuotaCode", equalTo("L-2AEEBF1A"))
            .body("RequestedQuotas.find { it.Id == '" + requestId + "' }.ServiceCode", equalTo("vpc"));
    }

    @Test
    void repeatedRequestsForSameQuotaReturnResourceAlreadyExists() {
        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.RequestServiceQuotaIncrease")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-B99A9384\",\"DesiredValue\":7000}")
        .when()
            .post("/")
        .then()
            .statusCode(200);

        given()
            .contentType(CONTENT_TYPE)
            .header("X-Amz-Target", "ServiceQuotasV20190624.RequestServiceQuotaIncrease")
            .header("Authorization", AUTH_HEADER)
            .body("{\"ServiceCode\":\"lambda\",\"QuotaCode\":\"L-B99A9384\",\"DesiredValue\":8000}")
        .when()
            .post("/")
        .then()
            .statusCode(400)
            .body("__type", equalTo("ResourceAlreadyExistsException"));
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
