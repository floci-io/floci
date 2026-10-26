package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code AWS::Route53::RecordSet} through the per-service provisioner: {@code Ref} is the record
 * name as the template wrote it, while the zone stores and lists the name fully qualified. It is
 * read back through an SSM parameter the template writes, because a stack status
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

    @Test
    void recordSetNameIsStoredFullyQualifiedAcrossCreateUpdateAndDelete() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-recordset-fqdn-" + suffix;
        String zoneName = suffix + ".cfn-fqdn-it.example.com";
        String firstName = "www." + zoneName;
        String secondName = "api." + zoneName;
        String template = """
                {
                  "Parameters": {
                    "RecordName": {"Type": "String"},
                    "Address": {"Type": "String"}
                  },
                  "Resources": {
                    "Zone": {
                      "Type": "AWS::Route53::HostedZone",
                      "Properties": {"Name": "%s"}
                    },
                    "Record": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": {"Ref": "Zone"},
                        "Name": {"Ref": "RecordName"},
                        "Type": "A",
                        "TTL": "300",
                        "ResourceRecords": [{"Ref": "Address"}]
                      }
                    },
                    "ZoneParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-recordset/%s/fqdn-zone", "Type": "String", "Value": {"Ref": "Zone"}}
                    }
                  }
                }
                """.formatted(zoneName, suffix);

        stackRequest("CreateStack", stackName, template, firstName, "10.0.0.1");
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String zoneId = parameterValue("/r53-recordset/" + suffix + "/fqdn-zone");
        String created = listResourceRecordSets(zoneId);
        assertTrue(created.contains("<Name>" + firstName + ".</Name>"), "name not fully qualified: " + created);
        assertFalse(created.contains("<Name>" + firstName + "</Name>"), "name stored as written: " + created);

        stackRequest("UpdateStack", stackName, template, "WWW." + zoneName, "10.0.0.3");
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String caseUpdated = listResourceRecordSets(zoneId);
        assertTrue(caseUpdated.contains("<Name>" + firstName + ".</Name>"),
                "case-only update lost its record: " + caseUpdated);
        assertTrue(caseUpdated.contains("10.0.0.3"), "case-only update lost its value: " + caseUpdated);

        // A rename is a new record identity, so the update must also find and remove the old record.
        stackRequest("UpdateStack", stackName, template, secondName, "10.0.0.2");
        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String updated = listResourceRecordSets(zoneId);
        assertTrue(updated.contains("<Name>" + secondName + ".</Name>"), "renamed record missing: " + updated);
        assertTrue(updated.contains("10.0.0.2"), "updated value missing: " + updated);
        assertFalse(updated.contains(firstName), "superseded record left in zone: " + updated);

        // The zone refuses to delete while it still holds the record, so a clean stack delete and a
        // zone that is gone prove the stack delete found the fully-qualified record and removed it.
        deleteStack(stackName);
        CfnStackWaits.awaitStackDeleted(stackName);
        given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/hostedzone/" + zoneId)
        .then().statusCode(404);
    }

    @Test
    void createStackFailsRatherThanTakeOverARecordTheRoute53ApiCreated() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-recordset-taken-" + suffix;
        String zoneName = suffix + ".cfn-taken-it.example.com";
        String recordName = "www." + zoneName;
        String zoneId = createHostedZone(zoneName, "cfn-taken-" + suffix);
        changeRecordSet(zoneId, "CREATE", recordName + ".", "192.0.2.10");
        String template = """
                {
                  "Resources": {
                    "Www": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": "%s",
                        "Name": "%s",
                        "Type": "A",
                        "TTL": "300",
                        "ResourceRecords": ["192.0.2.20"]
                      }
                    }
                  }
                }
                """.formatted(zoneId, recordName);

        try {
            createStack(stackName, template);
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            // AWS sends CREATE for a record the stack does not own yet, so the name being taken
            // fails the create and the rollback leaves the other owner's record alone.
            assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
            String failure = CfnStackWaits.failedResourceReason(stackName);
            assertTrue(failure.contains("already exists"), failure);
            String afterCreate = listResourceRecordSets(zoneId);
            assertTrue(afterCreate.contains("192.0.2.10"), "API record overwritten: " + afterCreate);
            assertFalse(afterCreate.contains("192.0.2.20"), "stack value written: " + afterCreate);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }

        String afterDelete = listResourceRecordSets(zoneId);
        assertTrue(afterDelete.contains("<Name>" + recordName + ".</Name>"), "API record deleted: " + afterDelete);
        assertTrue(afterDelete.contains("192.0.2.10"), "API record value lost: " + afterDelete);

        // A DELETE must name the record's current values, so it succeeding proves them unchanged.
        changeRecordSet(zoneId, "DELETE", recordName + ".", "192.0.2.10");
        given()
            .header("Authorization", ROUTE53_AUTH)
        .when().delete("/2013-04-01/hostedzone/" + zoneId)
        .then().statusCode(200);
    }

    private String createHostedZone(String zoneName, String callerReference) {
        String location = given()
            .contentType("application/xml")
            .header("Authorization", ROUTE53_AUTH)
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <CreateHostedZoneRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                      <Name>%s</Name>
                      <CallerReference>%s</CallerReference>
                    </CreateHostedZoneRequest>
                    """.formatted(zoneName, callerReference))
        .when().post("/2013-04-01/hostedzone")
        .then().statusCode(201)
            .extract().header("Location");
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private void changeRecordSet(String zoneId, String action, String name, String address) {
        given()
            .contentType("application/xml")
            .header("Authorization", ROUTE53_AUTH)
            .body("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <ChangeResourceRecordSetsRequest xmlns="https://route53.amazonaws.com/doc/2013-04-01/">
                      <ChangeBatch>
                        <Changes>
                          <Change>
                            <Action>%s</Action>
                            <ResourceRecordSet>
                              <Name>%s</Name>
                              <Type>A</Type>
                              <TTL>300</TTL>
                              <ResourceRecords>
                                <ResourceRecord><Value>%s</Value></ResourceRecord>
                              </ResourceRecords>
                            </ResourceRecordSet>
                          </Change>
                        </Changes>
                      </ChangeBatch>
                    </ChangeResourceRecordSetsRequest>
                    """.formatted(action, name, address))
        .when().post("/2013-04-01/hostedzone/" + zoneId + "/rrset")
        .then().statusCode(200);
    }

    private void stackRequest(String action, String stackName, String template, String recordName, String address) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", "RecordName")
            .formParam("Parameters.member.1.ParameterValue", recordName)
            .formParam("Parameters.member.2.ParameterKey", "Address")
            .formParam("Parameters.member.2.ParameterValue", address)
        .when().post("/").then().statusCode(200);
    }

    @Test
    void latencyRecordsKeepTheirRoutingPolicyAndHealthCheck() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-routing-" + suffix;
        String zoneName = suffix + ".cfn-routing-it.example.test";
        String template = """
                {
                  "Resources": {
                    "Zone": {
                      "Type": "AWS::Route53::HostedZone",
                      "Properties": {"Name": "%1$s"}
                    },
                    "Check": {
                      "Type": "AWS::Route53::HealthCheck",
                      "Properties": {
                        "HealthCheckConfig": {"Type": "HTTPS", "FullyQualifiedDomainName": "app.example.net",
                                              "ResourcePath": "/", "RequestInterval": 10, "FailureThreshold": 2},
                        "HealthCheckTags": [{"Key": "cloud", "Value": "aws"}]
                      }
                    },
                    "Latency": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": {"Ref": "Zone"},
                        "Name": "www.%1$s.",
                        "Type": "CNAME",
                        "TTL": "60",
                        "SetIdentifier": "aws",
                        "Region": "us-east-1",
                        "HealthCheckId": {"Ref": "Check"},
                        "ResourceRecords": ["app.example.net"]
                      }
                    },
                    "Weighted": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": {"Ref": "Zone"},
                        "Name": "api.%1$s.",
                        "Type": "A",
                        "TTL": "60",
                        "SetIdentifier": "blue",
                        "Weight": 70,
                        "ResourceRecords": ["10.0.0.7"]
                      }
                    },
                    "Primary": {
                      "Type": "AWS::Route53::RecordSet",
                      "Properties": {
                        "HostedZoneId": {"Ref": "Zone"},
                        "Name": "db.%1$s.",
                        "Type": "A",
                        "TTL": "60",
                        "SetIdentifier": "primary",
                        "Failover": "PRIMARY",
                        "HealthCheckId": {"Fn::GetAtt": ["Check", "HealthCheckId"]},
                        "ResourceRecords": ["10.0.0.8"]
                      }
                    },
                    "CheckRef": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-routing/%2$s/ref", "Type": "String", "Value": {"Ref": "Check"}}
                    },
                    "CheckAttr": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-routing/%2$s/attr", "Type": "String",
                                     "Value": {"Fn::GetAtt": ["Check", "HealthCheckId"]}}
                    },
                    "ZoneParam": {
                      "Type": "AWS::SSM::Parameter",
                      "Properties": {"Name": "/r53-routing/%2$s/zone", "Type": "String", "Value": {"Ref": "Zone"}}
                    }
                  }
                }
                """.formatted(zoneName, suffix);

        createStack(stackName, template);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String checkId = parameterValue("/r53-routing/" + suffix + "/ref");
        try {
            assertEquals(checkId, parameterValue("/r53-routing/" + suffix + "/attr"));
            assertFalse(checkId.startsWith("arn:aws:stub"), "health check was stubbed: " + checkId);

            String check = given()
                .header("Authorization", ROUTE53_AUTH)
            .when().get("/2013-04-01/healthcheck/" + checkId)
            .then().statusCode(200)
                .extract().asString();
            assertTrue(check.contains("<Type>HTTPS</Type>"), check);
            assertTrue(check.contains("<FullyQualifiedDomainName>app.example.net</FullyQualifiedDomainName>"), check);
            assertTrue(check.contains("<RequestInterval>10</RequestInterval>"), check);
            assertTrue(check.contains("<FailureThreshold>2</FailureThreshold>"), check);
            String tags = given()
                .header("Authorization", ROUTE53_AUTH)
            .when().get("/2013-04-01/tags/healthcheck/" + checkId)
            .then().statusCode(200)
                .extract().asString();
            assertTrue(tags.contains("<Key>cloud</Key>") && tags.contains("<Value>aws</Value>"), tags);

            String records = listResourceRecordSets(parameterValue("/r53-routing/" + suffix + "/zone"));
            assertTrue(records.contains("<SetIdentifier>aws</SetIdentifier><Region>us-east-1</Region>"), records);
            assertTrue(records.contains("<SetIdentifier>blue</SetIdentifier><Weight>70</Weight>"), records);
            assertTrue(records.contains("<SetIdentifier>primary</SetIdentifier><Failover>PRIMARY</Failover>"), records);
            assertEquals(2, records.split("<HealthCheckId>" + checkId + "</HealthCheckId>", -1).length - 1, records);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/healthcheck/" + checkId)
        .then().statusCode(404);
    }

    @Test
    void requestIntervalChangeReplacesTheCheckAndDeletesTheOldOneOnceTheUpdateCommits() {
        String stackName = "r53-hc-replace-" + Long.toString(System.nanoTime(), 36);
        createStack(stackName, healthCheckTemplate(30, false, false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String oldId = physicalId(stackName, "Check");
        try {
            updateStack(stackName, healthCheckTemplate(10, false, false));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            String newId = physicalId(stackName, "Check");
            assertNotEquals(oldId, newId);
            assertTrue(healthCheck(newId).contains("<RequestInterval>10</RequestInterval>"));
            healthCheckRequest(oldId).then().statusCode(404);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    @Test
    void retainedCheckOutlivesItsReplacement() {
        String stackName = "r53-hc-retain-" + Long.toString(System.nanoTime(), 36);
        createStack(stackName, healthCheckTemplate(30, true, false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String oldId = physicalId(stackName, "Check");
        try {
            updateStack(stackName, healthCheckTemplate(10, true, false));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            assertNotEquals(oldId, physicalId(stackName, "Check"));
            assertTrue(healthCheck(oldId).contains("<RequestInterval>30</RequestInterval>"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
            given().header("Authorization", ROUTE53_AUTH)
                .when().delete("/2013-04-01/healthcheck/" + oldId);
        }
    }

    @Test
    void failedUpdateRollsBackToTheOldCheck() {
        String stackName = "r53-hc-rollback-" + Long.toString(System.nanoTime(), 36);
        createStack(stackName, healthCheckTemplate(30, false, false));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String oldId = physicalId(stackName, "Check");
        try {
            updateStack(stackName, healthCheckTemplate(10, false, true));
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            assertEquals(oldId, physicalId(stackName, "Check"));
            assertTrue(healthCheck(oldId).contains("<RequestInterval>30</RequestInterval>"));
            String checks = given().header("Authorization", ROUTE53_AUTH)
                .when().get("/2013-04-01/healthcheck")
                .then().statusCode(200)
                .extract().asString();
            assertEquals(1, checks.split("<CallerReference>" + stackName + "/Check/", -1).length - 1, checks);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
    }

    /**
     * One TCP health check; {@code failing} adds a record set in a zone that does not exist, created
     * after the check, so the update fails once the check has been replaced.
     */
    private static String healthCheckTemplate(int requestInterval, boolean retain, boolean failing) {
        String policy = retain ? ", \"UpdateReplacePolicy\": \"Retain\"" : "";
        String broken = !failing ? "" : """
                ,
                "Broken": {
                  "Type": "AWS::Route53::RecordSet",
                  "DependsOn": "Check",
                  "Properties": {"HostedZoneId": "ZNOSUCHZONE00", "Name": "x.example.test.", "Type": "A",
                                 "TTL": "60", "ResourceRecords": ["10.0.0.1"]}
                }""";
        return """
                {
                  "Resources": {
                    "Check": {
                      "Type": "AWS::Route53::HealthCheck"%s,
                      "Properties": {
                        "HealthCheckConfig": {"Type": "TCP", "IPAddress": "10.0.0.5", "Port": 22,
                                              "RequestInterval": %d}
                      }
                    }%s
                  }
                }
                """.formatted(policy, requestInterval, broken);
    }

    private void updateStack(String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "UpdateStack")
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when().post("/").then().statusCode(200);
    }

    private String physicalId(String stackName, String logicalId) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStackResource")
            .formParam("StackName", stackName)
            .formParam("LogicalResourceId", logicalId)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("**.find { it.name() == 'PhysicalResourceId' }");
    }

    private Response healthCheckRequest(String id) {
        return given().header("Authorization", ROUTE53_AUTH)
            .when().get("/2013-04-01/healthcheck/" + id);
    }

    private String healthCheck(String id) {
        return healthCheckRequest(id).then().statusCode(200).extract().asString();
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
