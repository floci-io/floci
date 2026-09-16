package io.github.hectorvent.floci.services.oam;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class OamIntegrationTest {

    private static final String MONITORING =
            "AWS4-HMAC-SHA256 Credential=111111111111/20260911/us-east-1/oam/aws4_request";
    private static final String SOURCE =
            "AWS4-HMAC-SHA256 Credential=222222222222/20260911/us-east-1/oam/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sinkLinkLifecycleWorksAcrossAccounts() {
        String sinkArn = given()
                .header("Authorization", MONITORING)
                .contentType("application/json")
                .body("{\"Name\":\"central-observability\"}")
            .when().post("/CreateSink")
            .then().statusCode(200)
                .body("Name", equalTo("central-observability"))
                .body("Arn", startsWith("arn:aws:oam:us-east-1:111111111111:sink/"))
                .extract().path("Arn");

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::222222222222:root\"},"
                + "\"Action\":[\"oam:CreateLink\",\"oam:UpdateLink\"],\"Resource\":\"" + sinkArn + "\","
                + "\"Condition\":{\"ForAllValues:StringEquals\":{\"oam:ResourceTypes\":["
                + "\"AWS::CloudWatch::Metric\",\"AWS::Logs::LogGroup\"]}}}]}";

        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("SinkIdentifier", sinkArn, "Policy", policy))
            .when().post("/PutSinkPolicy")
            .then().statusCode(200).body("SinkArn", equalTo(sinkArn));

        String linkArn = given().header("Authorization", SOURCE).contentType("application/json")
                .body(java.util.Map.of(
                        "SinkIdentifier", sinkArn,
                        "LabelTemplate", "source-account",
                        "ResourceTypes", java.util.List.of("AWS::CloudWatch::Metric")))
            .when().post("/CreateLink")
            .then().statusCode(200)
                .body("SinkArn", equalTo(sinkArn))
                .body("ResourceTypes", contains("AWS::CloudWatch::Metric"))
                .extract().path("Arn");

        given().header("Authorization", SOURCE).contentType("application/json").body("{}")
            .when().post("/ListLinks")
            .then().statusCode(200).body("Items.Arn", contains(linkArn));

        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("SinkIdentifier", sinkArn))
            .when().post("/ListAttachedLinks")
            .then().statusCode(200).body("Items.LinkArn", contains(linkArn));

        given().header("Authorization", SOURCE).contentType("application/json")
                .body(java.util.Map.of(
                        "Identifier", linkArn,
                        "ResourceTypes", java.util.List.of("AWS::CloudWatch::Metric", "AWS::Logs::LogGroup"),
                        "LinkConfiguration", java.util.Map.of(
                                "MetricConfiguration", java.util.Map.of("Filter", "Namespace LIKE 'AWS/%'"))))
            .when().post("/UpdateLink")
            .then().statusCode(200)
                .body("ResourceTypes.size()", equalTo(2))
                .body("LinkConfiguration.MetricConfiguration.Filter", equalTo("Namespace LIKE 'AWS/%'"));

        given().header("Authorization", SOURCE).contentType("application/json")
                .body(java.util.Map.of("Identifier", linkArn))
            .when().post("/DeleteLink")
            .then().statusCode(200);

        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("Identifier", sinkArn))
            .when().post("/DeleteSink")
            .then().statusCode(200);
    }

    @Test
    void sinkPolicyRejectsUnauthorizedSourceAccount() {
        String sinkArn = given().header("Authorization", MONITORING).contentType("application/json")
                .body("{\"Name\":\"policy-test\"}")
            .when().post("/CreateSink")
            .then().statusCode(200).extract().path("Arn");

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":{\"AWS\":\"arn:aws:iam::333333333333:root\"},"
                + "\"Action\":\"oam:CreateLink\",\"Resource\":\"*\"}]}";
        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("SinkIdentifier", sinkArn, "Policy", policy))
            .when().post("/PutSinkPolicy")
            .then().statusCode(200);

        given().header("Authorization", SOURCE).contentType("application/json")
                .body(java.util.Map.of(
                        "SinkIdentifier", sinkArn,
                        "LabelTemplate", "denied",
                        "ResourceTypes", java.util.List.of("AWS::CloudWatch::Metric")))
            .when().post("/CreateLink")
            .then().statusCode(400).body("__type", containsString("InvalidParameterException"));

        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("Identifier", sinkArn))
            .when().post("/DeleteSink")
            .then().statusCode(200);
    }
    @Test
    void unsupportedOrganizationConditionDoesNotBecomeWildcardAllow() {
        String sinkArn = given().header("Authorization", MONITORING).contentType("application/json")
                .body("{\"Name\":\"org-policy-test\"}")
            .when().post("/CreateSink")
            .then().statusCode(200).extract().path("Arn");

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":\"Allow\","
                + "\"Principal\":\"*\",\"Action\":\"oam:CreateLink\",\"Resource\":\"*\","
                + "\"Condition\":{\"StringEquals\":{\"aws:PrincipalOrgID\":\"o-example\"}}}]}";
        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("SinkIdentifier", sinkArn, "Policy", policy))
            .when().post("/PutSinkPolicy").then().statusCode(200);

        given().header("Authorization", SOURCE).contentType("application/json")
                .body(java.util.Map.of("SinkIdentifier", sinkArn, "LabelTemplate", "source",
                        "ResourceTypes", java.util.List.of("AWS::CloudWatch::Metric")))
            .when().post("/CreateLink")
            .then().statusCode(400).body("__type", containsString("InvalidParameterException"));

        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("Identifier", sinkArn))
            .when().post("/DeleteSink").then().statusCode(200);
    }

    @Test
    void explicitDenyOverridesMatchingAllow() {
        String sinkArn = given().header("Authorization", MONITORING).contentType("application/json")
                .body("{\"Name\":\"explicit-deny-test\"}")
            .when().post("/CreateSink")
            .then().statusCode(200).extract().path("Arn");

        String policy = "{\"Version\":\"2012-10-17\",\"Statement\":["
                + "{\"Effect\":\"Allow\",\"Principal\":\"*\",\"Action\":\"oam:CreateLink\",\"Resource\":\"*\"},"
                + "{\"Effect\":\"Deny\",\"Principal\":{\"AWS\":\"arn:aws:iam::222222222222:root\"},"
                + "\"Action\":\"oam:CreateLink\",\"Resource\":\"*\"}]}";
        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("SinkIdentifier", sinkArn, "Policy", policy))
            .when().post("/PutSinkPolicy").then().statusCode(200);

        given().header("Authorization", SOURCE).contentType("application/json")
                .body(java.util.Map.of("SinkIdentifier", sinkArn, "LabelTemplate", "source",
                        "ResourceTypes", java.util.List.of("AWS::CloudWatch::Metric")))
            .when().post("/CreateLink")
            .then().statusCode(400).body("__type", containsString("InvalidParameterException"));

        given().header("Authorization", MONITORING).contentType("application/json")
                .body(java.util.Map.of("Identifier", sinkArn))
            .when().post("/DeleteSink").then().statusCode(200);
    }

}
