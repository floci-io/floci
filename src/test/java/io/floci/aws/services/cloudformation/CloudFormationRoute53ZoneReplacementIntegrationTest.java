package io.floci.aws.services.cloudformation;

import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.aws.services.cloudformation.SsmParameterTestSupport.parameterValue;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A hosted zone replaced by a Name change, with a record set in it that refers to the zone. */
@QuarkusTest
class CloudFormationRoute53ZoneReplacementIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String ROUTE53_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/route53/aws4_request";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aReplacedZoneMovesItsRecordSetsAndTheOldZoneIsDeletedEmpty() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-zone-repl-" + suffix;
        String zoneParam = "/r53-zone-repl/" + suffix + "/zone";

        stack("CreateStack", stackName, template(suffix, "a"));
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        try {
            String oldZone = parameterValue(zoneParam);
            assertTrue(records(oldZone, 200).contains("www.a." + suffix));

            stack("UpdateStack", stackName, template(suffix, "b"));
            CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE", CFN_AUTH);

            String newZone = parameterValue(zoneParam);
            assertNotEquals(oldZone, newZone, "a Name change replaces the zone");
            String moved = records(newZone, 200);
            assertTrue(moved.contains("www.b." + suffix), "the record set is in the new zone: " + moved);
            assertEquals(404, given().header("Authorization", ROUTE53_AUTH)
                    .when().get("/2013-04-01/hostedzone/" + oldZone).statusCode(),
                    "the old zone was empty and is deleted");
        } finally {
            stack("DeleteStack", stackName, null);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    private static String template(String suffix, String label) {
        return """
                {"Resources": {
                  "Zone": {"Type": "AWS::Route53::HostedZone",
                           "Properties": {"Name": "%2$s.%1$s.cfn-it.example.com"}},
                  "Www": {"Type": "AWS::Route53::RecordSet",
                          "Properties": {"HostedZoneId": {"Ref": "Zone"}, "Name": "www.%2$s.%1$s.cfn-it.example.com",
                                         "Type": "A", "TTL": "300", "ResourceRecords": ["10.0.0.1"]}},
                  "ZoneParam": {"Type": "AWS::SSM::Parameter",
                                "Properties": {"Name": "/r53-zone-repl/%1$s/zone", "Type": "String",
                                               "Value": {"Ref": "Zone"}}}
                }}
                """.formatted(suffix, label);
    }

    private static void stack(String action, String stackName, String template) {
        RequestSpecification request = given()
                .contentType("application/x-www-form-urlencoded")
                .header("Authorization", CFN_AUTH)
                .formParam("Action", action)
                .formParam("StackName", stackName);
        if (template != null) {
            request = request.formParam("TemplateBody", template);
        }
        request.when().post("/").then().statusCode(200);
    }

    private static String records(String zoneId, int status) {
        return given().header("Authorization", ROUTE53_AUTH)
                .when().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
                .then().statusCode(status).extract().asString();
    }
}
