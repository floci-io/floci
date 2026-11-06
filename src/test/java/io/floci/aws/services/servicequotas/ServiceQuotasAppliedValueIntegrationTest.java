package io.floci.aws.services.servicequotas;

import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Map;

import static io.floci.aws.testing.EnforcementFixtures.auth;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@QuarkusTest
@TestProfile(ServiceQuotasAppliedValueIntegrationTest.AppliedQuotaProfile.class)
class ServiceQuotasAppliedValueIntegrationTest {

    private static final String CODE = "L-1216C47A";

    public static class AppliedQuotaProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci.services.servicequotas.standard-on-demand-vcpus", "7");
        }
    }

    @BeforeAll
    static void configureContentTypes() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn"})
    void appliedGetReportsConfiguredValue(String region, String partition) {
        quotaRequest("GetServiceQuota", "{\"ServiceCode\":\"ec2\",\"QuotaCode\":\"" + CODE + "\"}", region)
                .then().statusCode(200)
                .body("Quota.Value", equalTo(7.0f))
                .body("Quota.QuotaArn", equalTo("arn:" + partition + ":servicequotas:" + region
                        + ":111111111111:ec2/" + CODE));
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn"})
    void appliedListReportsConfiguredValue(String region, String partition) {
        quotaRequest("ListServiceQuotas", "{\"ServiceCode\":\"ec2\",\"QuotaCode\":\"" + CODE + "\"}", region)
                .then().statusCode(200)
                .body("Quotas.size()", equalTo(1))
                .body("Quotas[0].Value", equalTo(7.0f))
                .body("Quotas[0].QuotaArn", equalTo("arn:" + partition + ":servicequotas:" + region
                        + ":111111111111:ec2/" + CODE));
    }

    @ParameterizedTest
    @CsvSource({"us-east-1,aws", "cn-north-1,aws-cn"})
    void awsDefaultReadsKeepFive(String region, String partition) {
        quotaRequest("GetAWSDefaultServiceQuota", "{\"ServiceCode\":\"ec2\",\"QuotaCode\":\"" + CODE + "\"}", region)
                .then().statusCode(200).body("Quota.Value", equalTo(5.0f));
        quotaRequest("ListAWSDefaultServiceQuotas", "{\"ServiceCode\":\"ec2\"}", region)
                .then().statusCode(200).body("Quotas.size()", equalTo(4))
                .body("Quotas.find { it.QuotaCode == 'L-1216C47A' }.Value", equalTo(5.0f));
    }

    @ParameterizedTest
    @CsvSource({"ListServiceQuotas,7", "ListAWSDefaultServiceQuotas,5"})
    void bothListsRetainPaginationAndGenericRows(String action, float expectedValue) {
        Response first = quotaRequest(action, "{\"ServiceCode\":\"ec2\",\"MaxResults\":1}", "us-east-1")
                .then().statusCode(200).body("Quotas.size()", equalTo(1))
                .body("Quotas[0].QuotaCode", equalTo(CODE)).body("Quotas[0].Value", equalTo(expectedValue))
                .extract().response();
        String token = first.path("NextToken");
        Response rest = quotaRequest(action,
                "{\"ServiceCode\":\"ec2\",\"NextToken\":\"" + token + "\"}", "us-east-1")
                .then().statusCode(200).body("Quotas.size()", equalTo(3)).extract().response();
        assertEquals(List.of(5000.0f, 5000.0f, 5000.0f), rest.path("Quotas.Value"));
        assertNull(rest.path("NextToken"));
    }

    @ParameterizedTest
    @CsvSource({"GetServiceQuota", "GetAWSDefaultServiceQuota"})
    void unrelatedCuratedValuesAndErrorsRemainUnchanged(String action) {
        quotaRequest(action, "{\"ServiceCode\":\"vpc\",\"QuotaCode\":\"L-2AEEBF1A\"}", "us-east-1")
                .then().statusCode(200).body("Quota.Value", equalTo(20.0f));
        quotaRequest(action, "{\"ServiceCode\":\"ec2\",\"QuotaCode\":\"L-UNKNOWN\"}", "us-east-1")
                .then().statusCode(400).body("__type", equalTo("NoSuchResourceException"));
    }

    private Response quotaRequest(String action, String body, String region) {
        return given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "ServiceQuotasV20190624." + action)
                .header("Authorization", auth("111111111111", "20261018", region, "servicequotas"))
                .body(body).when().post("/");
    }
}
