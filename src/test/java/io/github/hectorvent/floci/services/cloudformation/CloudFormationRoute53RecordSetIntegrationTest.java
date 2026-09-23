package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::Route53::RecordSet} through the per-service provisioner: {@code Ref} is the record
 * name. It is read back through an SSM parameter the template writes, because a stack status
 * alone proves nothing: an unowned type is stubbed CREATE_COMPLETE with an {@code arn:aws:stub}
 * physical id, which the parameter would expose.
 */
@QuarkusTest
class CloudFormationRoute53RecordSetIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String SSM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ssm/aws4_request";
    private static final String ROUTE53_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/route53/aws4_request";
    private static final Duration STACK_DELETE_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration STACK_DELETE_POLL_INTERVAL = Duration.ofMillis(50);

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void recordSetRefResolvesToTheRecordName() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-recordset-" + suffix;
        String recordName = "www." + suffix + ".cfn-it.example.com";
        String template = """
                {
                  "Resources": {
                    "Zone": {
                      "Type": "AWS::Route53::HostedZone",
                      "Properties": {"Name": "%s.cfn-it.example.com"}
                    },
                    "Www": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": {"Ref": "Zone"},
                        "Name": "%s",
                        "Type": "A",
                        "TTL": "300",
                        "ResourceRecords": ["10.0.0.1"]
                      }
                    },
                    "RefParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-recordset/%s/ref", "Type": "String", "Value": {"Ref": "Www"}}
                    },
                    "ZoneParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-recordset/%s/zone", "Type": "String", "Value": {"Ref": "Zone"}}
                    }
                  }
                }
                """.formatted(suffix, recordName, suffix, suffix);

        createStack(stackName, template);
        assertStackStatus(stackName, "CREATE_COMPLETE");
        try {
            assertEquals(recordName, parameterValue("/r53-recordset/" + suffix + "/ref"));

            // A stack status proves nothing on its own: read the record back from the zone to
            // confirm the provisioner wrote it (not stubbed) rather than leaving the zone empty.
            String zoneId = parameterValue("/r53-recordset/" + suffix + "/zone");
            String records = listResourceRecordSets(zoneId);
            assertTrue(records.contains(recordName), "record not written to zone: " + records);
            assertTrue(records.contains("10.0.0.1"), "record value not written to zone: " + records);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    private String listResourceRecordSets(String zoneId) {
        return given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/hostedzone/" + zoneId + "/rrset")
        .then().statusCode(200)
            .extract().asString();
    }

    private void createStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "CreateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private void assertStackStatus(String stackName, String status) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .body(containsString("<StackStatus>" + status + "</StackStatus>"));
    }

    private void deleteStack(String stackName) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DeleteStack")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200);
    }

    private String parameterValue(String name) {
        return given()
            .contentType("application/x-amz-json-1.1")
            .header("Authorization", SSM_AUTH)
            .header("X-Amz-Target", "AmazonSSM.GetParameter")
            .body("{\"Name\":\"" + name + "\"}")
        .when().post("/").then().statusCode(200)
            .extract().jsonPath().getString("Parameter.Value");
    }
}
