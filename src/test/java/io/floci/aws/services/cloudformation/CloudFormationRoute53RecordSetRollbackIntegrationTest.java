package io.floci.aws.services.cloudformation;

import io.floci.aws.services.cloudformation.provisioners.CfnRollback;
import io.floci.aws.testing.CfnStackWaits;
import io.floci.aws.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.floci.aws.services.cloudformation.SsmParameterTestSupport.parameterValue;
import static io.floci.aws.testing.CfnRequests.submit;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A record set whose update is rolled back because a later resource fails. */
@QuarkusTest
class CloudFormationRoute53RecordSetRollbackIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String SSM_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/ssm/aws4_request";
    private static final String ROUTE53_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/route53/aws4_request";

    @Inject
    CloudFormationService cloudFormationService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void aTtlChangeIsPutBackWhenALaterResourceFails() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-record-rb-" + suffix;
        String foreign = "/r53-record-rb/" + suffix + "/foreign";
        putParameter(foreign);

        submit("CreateStack", stackName, template(suffix, "300", null), CFN_AUTH);
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        try {
            String zone = parameterValue("/r53-record-rb/" + suffix + "/zone");

            submit("UpdateStack", stackName, template(suffix, "60", foreign), CFN_AUTH);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_ROLLBACK_COMPLETE", CFN_AUTH);

            String records = given().header("Authorization", ROUTE53_AUTH)
                    .when().get("/2013-04-01/hostedzone/" + zone + "/rrset")
                    .then().statusCode(200).extract().asString();
            assertTrue(records.contains("<TTL>300</TTL>") && !records.contains("<TTL>60</TTL>"),
                    "the record has the TTL it had before the update: " + records);
            assertEquals(zone, parameterValue("/r53-record-rb/" + suffix + "/zone"));
        } finally {
            submit("DeleteStack", stackName, null, CFN_AUTH);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void aCommittedUpdateLeavesNoRollbackSnapshotOnTheRecordSet() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-record-commit-" + suffix;

        submit("CreateStack", stackName, template(suffix, "300", null), CFN_AUTH);
        CfnStackWaits.awaitStatus(stackName, "CREATE_COMPLETE", CFN_AUTH);
        try {
            submit("UpdateStack", stackName, template(suffix, "60", null), CFN_AUTH);
            CfnStackWaits.awaitStatus(stackName, "UPDATE_COMPLETE", CFN_AUTH);

            assertFalse(cloudFormationService.describeStacks(stackName, "us-east-1").get(0).getResources()
                    .get("Www").getAttributes().containsKey(CfnRollback.ROUTE53_RECORD_UPDATE_SNAPSHOT_ATTR));
        } finally {
            submit("DeleteStack", stackName, null, CFN_AUTH);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    private static String template(String suffix, String ttl, String failingParameter) {
        String failing = failingParameter == null ? "" : """
                ,"Boom": {"Type": "AWS::SSM::Parameter", "DependsOn": "Www",
                          "Properties": {"Name": "%s", "Type": "String", "Value": "x"}}""".formatted(failingParameter);
        return """
                {"Resources": {
                  "Zone": {"Type": "AWS::Route53::HostedZone",
                           "Properties": {"Name": "a.%1$s.cfn-it.example.com"}},
                  "Www": {"Type": "AWS::Route53::RecordSet",
                          "Properties": {"HostedZoneId": {"Ref": "Zone"}, "Name": "www.a.%1$s.cfn-it.example.com",
                                         "Type": "A", "TTL": "%2$s", "ResourceRecords": ["10.0.0.1"]}},
                  "ZoneParam": {"Type": "AWS::SSM::Parameter",
                                "Properties": {"Name": "/r53-record-rb/%1$s/zone", "Type": "String",
                                               "Value": {"Ref": "Zone"}}}
                  %3$s
                }}
                """.formatted(suffix, ttl, failing);
    }

    private static void putParameter(String name) {
        given().contentType("application/x-amz-json-1.1").header("Authorization", SSM_AUTH)
                .header("X-Amz-Target", "AmazonSSM.PutParameter")
                .body("{\"Name\":\"" + name + "\",\"Value\":\"foreign\",\"Type\":\"String\"}")
                .when().post("/").then().statusCode(200);
    }
}
