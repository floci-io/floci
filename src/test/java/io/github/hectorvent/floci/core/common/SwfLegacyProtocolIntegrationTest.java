package io.github.hectorvent.floci.core.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(StrictProtocolClaimingIntegrationTest.StrictClaimingProfile.class)
class SwfLegacyProtocolIntegrationTest {

    private static final String LEGACY_PREFIX = "com.amazonaws.swf.service.model.SimpleWorkflowService.";
    private static final String MODERN_PREFIX = "SimpleWorkflowService.";
    private static final String LEGACY_JSON = "application/json; charset=UTF-8";
    private static final String MODERN_JSON = "application/x-amz-json-1.0";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", LEGACY_JSON})
    void legacyRegistrationsShareStateWithModernClients(String mediaType) {
        String domain = "legacy-swf-" + UUID.randomUUID();
        call(mediaType, LEGACY_PREFIX, "RegisterDomain", """
                {"name":"%s","workflowExecutionRetentionPeriodInDays":"7"}
                """.formatted(domain)).then().statusCode(200).contentType("application/json");
        call(MODERN_JSON, MODERN_PREFIX, "DescribeDomain", "{\"name\":\"" + domain + "\"}")
                .then().statusCode(200).body("domainInfo.name", equalTo(domain));

        call(mediaType, LEGACY_PREFIX, "RegisterActivityType", """
                {"domain":"%s","name":"work","version":"1"}
                """.formatted(domain)).then().statusCode(200);
        call(MODERN_JSON, MODERN_PREFIX, "DescribeActivityType", """
                {"domain":"%s","activityType":{"name":"work","version":"1"}}
                """.formatted(domain)).then().statusCode(200).body("typeInfo.status", equalTo("REGISTERED"));

        call(MODERN_JSON, MODERN_PREFIX, "RegisterWorkflowType", """
                {"domain":"%s","name":"flow","version":"1"}
                """.formatted(domain)).then().statusCode(200);
        call(mediaType, LEGACY_PREFIX, "DescribeWorkflowType", """
                {"domain":"%s","workflowType":{"name":"flow","version":"1"}}
                """.formatted(domain)).then().statusCode(200).body("typeInfo.status", equalTo("REGISTERED"));
    }

    @Test
    void legacyDuplicateActivityRegistrationPreservesTypedBotoFaults() {
        String domain = "legacy-duplicate-" + UUID.randomUUID();
        String domainBody = """
                {"name":"%s","workflowExecutionRetentionPeriodInDays":"7"}
                """.formatted(domain);
        call(MODERN_JSON, MODERN_PREFIX, "RegisterDomain", domainBody).then().statusCode(200);
        String activity = "{\"domain\":\"" + domain + "\",\"name\":\"work\",\"version\":\"1\"}";
        call(MODERN_JSON, MODERN_PREFIX, "RegisterActivityType", activity).then().statusCode(200);
        call(LEGACY_JSON, LEGACY_PREFIX, "RegisterActivityType", activity).then().statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#TypeAlreadyExistsFault"));
    }

    @ParameterizedTest
    @CsvSource(textBlock = """
            SimpleWorkflowService., application/json
            SimpleWorkflowService., application/json; charset=UTF-8
            SimpleWorkflowService., application/x-amz-json-1.0
            com.amazonaws.swf.service.model.SimpleWorkflowService., application/json
            com.amazonaws.swf.service.model.SimpleWorkflowService., application/json; charset=UTF-8
            com.amazonaws.swf.service.model.SimpleWorkflowService., application/x-amz-json-1.0
            """)
    void acceptedWireFormatsShareDomainStateAndTypedFaults(String prefix, String mediaType) {
        String domain = "wire-swf-" + UUID.randomUUID();
        String registration = """
                {"name":"%s","workflowExecutionRetentionPeriodInDays":"7"}
                """.formatted(domain);
        call(MODERN_JSON, MODERN_PREFIX, "RegisterDomain", registration).then().statusCode(200);
        call(mediaType, prefix, "DescribeDomain", "{\"name\":\"" + domain + "\"}")
                .then().statusCode(200).body("domainInfo.name", equalTo(domain));
        call(mediaType, prefix, "RegisterDomain", registration).then().statusCode(400)
                .body("__type", equalTo("com.amazonaws.swf.base.model#DomainAlreadyExistsFault"));
    }

    @ParameterizedTest
    @ValueSource(strings = {LEGACY_PREFIX, MODERN_PREFIX})
    void cborDoesNotClaimSwfTargets(String prefix) throws Exception {
        byte[] response = given().contentType("application/x-amz-cbor-1.1")
                .header("X-Amz-Target", prefix + "ListDomains")
                .body(new byte[]{(byte) 0xbf, (byte) 0xff})
                .post("/").then().statusCode(404).contentType("application/x-amz-cbor-1.1")
                .extract().asByteArray();

        assertEquals("UnknownOperationException",
                new ObjectMapper(new CBORFactory()).readTree(response).path("__type").asText());
    }

    @Test
    void plainJsonWithoutTargetReportsTheMissingHeader() {
        given().contentType(LEGACY_JSON).body("{}").post("/")
                .then().statusCode(404).body("message", equalTo("Missing X-Amz-Target header."));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "com.amazonaws.swf.service.model.SimpleWorkflowServiceOther.ListDomains",
            "AmazonSSM.DescribeParameters"
    })
    void plainJsonDoesNotClaimOtherTargets(String target) {
        call(LEGACY_JSON, "", target, "{}").then().statusCode(404);
    }

    private static Response call(String mediaType, String prefix, String action, String body) {
        return given().contentType(mediaType)
                .header("X-Amz-Target", prefix + action)
                .header("Content-Encoding", "amz-1.0")
                .body(body).post("/");
    }
}
