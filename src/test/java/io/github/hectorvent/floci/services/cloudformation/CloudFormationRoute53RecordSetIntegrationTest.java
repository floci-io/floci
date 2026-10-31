package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.route53.Route53HostedZoneTestSupport;
import io.github.hectorvent.floci.services.route53.Route53Service;
import io.github.hectorvent.floci.testing.CfnStackWaits;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.mockito.InjectSpy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;

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
    /** A record set in a zone that does not exist, created after the group, so it fails the update. */
    private static final String FAILS_AFTER_GROUP = """
            ,
            "Broken": {
              "Type": "AWS::Route53::RecordSet",
              "DependsOn": "Group",
              "Properties": {"HostedZoneId": "ZNOSUCHZONE00", "Name": "x.example.test.", "Type": "A",
                             "TTL": "60", "ResourceRecords": ["10.0.0.1"]}
            }""";

    @InjectSpy
    Route53Service route53Service;

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
        return Route53HostedZoneTestSupport.createZone(zoneName, callerReference, ROUTE53_AUTH);
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

    @Test
    void recordGroupCreatesUpdatesAndDeletesItsRecordsUnderAStableRef() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String zoneId = createHostedZone(zoneName, "cfn-group-" + suffix);
        String unmanaged = "unmanaged." + zoneName;
        changeRecordSet(zoneId, "CREATE", unmanaged, "192.0.2.9");

        createStack(stackName, groupTemplate(zoneId,
                groupRecord("first." + zoneName, "192.0.2.1") + "," + groupRecord("second." + zoneName, "192.0.2.2")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String firstChange;
        try {
            // AWS documents Ref as the group's name, for example MyRecordSetGroup, and Fn::GetAtt Id as
            // the ID GetChange takes.
            assertEquals("Group", stackOutput(stackName, "GroupRef"));
            assertEquals("Group", physicalId(stackName, "Group"));
            firstChange = stackOutput(stackName, "GroupChange");
            assertChangeInSync(firstChange);
            String created = listResourceRecordSets(zoneId);
            assertTrue(created.contains("<Name>first." + zoneName + ".</Name>"), created);
            assertTrue(created.contains("<Name>second." + zoneName + ".</Name>"), created);

            updateStack(stackName, groupTemplate(zoneId,
                    groupRecord("first." + zoneName, "192.0.2.3") + ","
                            + groupRecord("third." + zoneName, "192.0.2.4")));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertEquals("Group", stackOutput(stackName, "GroupRef"));
            String secondChange = stackOutput(stackName, "GroupChange");
            assertNotEquals(firstChange, secondChange);
            assertChangeInSync(secondChange);
            String updated = listResourceRecordSets(zoneId);
            assertTrue(updated.contains("192.0.2.3"), updated);
            assertFalse(updated.contains("192.0.2.1"), updated);
            assertTrue(updated.contains("<Name>third." + zoneName + ".</Name>"), updated);
            assertFalse(updated.contains("second." + zoneName), updated);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }

        String deleted = listResourceRecordSets(zoneId);
        assertFalse(deleted.contains("first." + zoneName), deleted);
        assertFalse(deleted.contains("third." + zoneName), deleted);
        assertTrue(deleted.contains("192.0.2.9"), "a record the stack never wrote was deleted: " + deleted);
        changeRecordSet(zoneId, "DELETE", unmanaged, "192.0.2.9");
        deleteHostedZone(zoneId);
    }

    @Test
    void recordGroupKeepsTheRoutingPolicyOfEachRecordSet() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-routing-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.test";
        String zoneId = createHostedZone(zoneName, "cfn-group-routing-" + suffix);
        String template = """
                {
                  "Parameters": {"BlueWeight": {"Type": "Number"}},
                  "Resources": {
                    "Check": {
                      "Type": "AWS::Route53::HealthCheck",
                      "Properties": {"HealthCheckConfig": {"Type": "TCP", "IPAddress": "10.0.0.5", "Port": 22}}
                    },
                    "Group": {
                      "Type": "AWS::Route53::RecordSetGroup",
                      "Properties": {
                        "HostedZoneName": "%1$s.",
                        "RecordSets": [
                          {"Name": "api.%1$s.", "Type": "A", "TTL": "60", "SetIdentifier": "blue",
                           "Weight": {"Ref": "BlueWeight"}, "HealthCheckId": {"Ref": "Check"},
                           "ResourceRecords": ["10.0.0.7"]},
                          {"Name": "api.%1$s.", "Type": "A", "TTL": "60", "SetIdentifier": "green",
                           "Weight": 30, "ResourceRecords": ["10.0.0.8"]}
                        ]
                      }
                    }
                  }
                }
                """.formatted(zoneName);

        parameterizedStackRequest(stackName, "CreateStack", template, "BlueWeight", "70");
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        try {
            String checkId = physicalId(stackName, "Check");
            String created = listResourceRecordSets(zoneId);
            assertTrue(created.contains("<SetIdentifier>blue</SetIdentifier><Weight>70</Weight>"), created);
            assertTrue(created.contains("<SetIdentifier>green</SetIdentifier><Weight>30</Weight>"), created);
            assertTrue(created.contains("<HealthCheckId>" + checkId + "</HealthCheckId>"), created);

            // The update's DELETE of each record the group wrote must name it exactly, routing
            // policy included, or Route 53 refuses the whole batch.
            parameterizedStackRequest(stackName, "UpdateStack", template, "BlueWeight", "60");
            CfnStackWaits.StackState updated = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_COMPLETE", updated.status(), updated.reason());
            String records = listResourceRecordSets(zoneId);
            assertTrue(records.contains("<SetIdentifier>blue</SetIdentifier><Weight>60</Weight>"), records);
            assertFalse(records.contains("<Weight>70</Weight>"), records);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }

        String deleted = listResourceRecordSets(zoneId);
        assertFalse(deleted.contains("api." + zoneName), deleted);
        deleteHostedZone(zoneId);
    }

    @Test
    void recordGroupZoneChangeIsAReplacementThatMovesItsRecords() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-move-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-move-old-" + suffix);
        String newZone = createHostedZone(zoneName, "cfn-group-move-new-" + suffix);
        String name = "www." + zoneName;
        createStack(stackName, groupTemplate(oldZone, groupRecord(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        try {
            // HostedZoneId is create-only in the registry schema, so the change set reports the move as
            // a replacement.
            changeSetRequest("CreateChangeSet", stackName, "move")
                    .formParam("ChangeSetType", "UPDATE")
                    .formParam("TemplateBody", groupTemplate(newZone, groupRecord(name, "192.0.2.2")))
                .when().post("/").then().statusCode(200);
            changeSetRequest("DescribeChangeSet", stackName, "move")
                .when().post("/").then().statusCode(200)
                .body(containsString("<Replacement>True</Replacement>"));
            changeSetRequest("ExecuteChangeSet", stackName, "move")
                .when().post("/").then().statusCode(200);
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            assertFalse(listResourceRecordSets(oldZone).contains(name), "old zone kept the record");
            assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"), "new zone lacks the record");
            assertEquals("Group", stackOutput(stackName, "GroupRef"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        assertFalse(listResourceRecordSets(newZone).contains(name));
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void recordGroupIsPutBackWhenALaterResourceFailsTheUpdate(boolean moveZone) {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-rollback-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-rollback-old-" + suffix);
        String targetZone = moveZone ? createHostedZone(zoneName, "cfn-group-rollback-new-" + suffix) : oldZone;
        String name = "www." + zoneName;
        createStack(stackName, groupTemplate(oldZone, groupRecord(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        String change = stackOutput(stackName, "GroupChange");
        try {
            updateStack(stackName, groupTemplate(targetZone, groupRecord(name, "192.0.2.2"), "", FAILS_AFTER_GROUP));
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            String restored = listResourceRecordSets(oldZone);
            assertTrue(restored.contains("192.0.2.1"), restored);
            assertFalse(restored.contains("192.0.2.2"), restored);
            if (moveZone) {
                assertFalse(listResourceRecordSets(targetZone).contains(name), "the moved record was left behind");
            }
            assertEquals(change, stackOutput(stackName, "GroupChange"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        assertFalse(listResourceRecordSets(oldZone).contains(name), "the restored record outlived the stack");
        deleteHostedZone(oldZone);
        if (moveZone) {
            deleteHostedZone(targetZone);
        }
    }

    @Test
    void retainedRecordGroupLeavesItsOldRecordsWhenItMovesZone() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-retain-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-retain-old-" + suffix);
        String newZone = createHostedZone(zoneName, "cfn-group-retain-new-" + suffix);
        String name = "www." + zoneName;
        String retain = ", \"UpdateReplacePolicy\": \"Retain\"";
        createStack(stackName, groupTemplate(oldZone, groupRecord(name, "192.0.2.1"), retain, ""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        try {
            updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.2"), retain, ""));
            assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"), "the retained record was deleted");
            assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"), "new zone lacks the record");
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        assertFalse(listResourceRecordSets(newZone).contains(name));
        assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"), "the stack delete took the retained record");
        changeRecordSet(oldZone, "DELETE", name, "192.0.2.1");
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @Test
    void recordGroupMoveGivesUpOnAnOldRecordChangedOutsideTheStack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-drift-move-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-drift-old-" + suffix);
        String newZone = createHostedZone(zoneName, "cfn-group-drift-new-" + suffix);
        String name = "www." + zoneName;
        createStack(stackName, groupTemplate(oldZone, groupRecord(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        changeRecordSet(oldZone, "UPSERT", name, "192.0.2.9");
        try {
            // The cleanup's DELETE names the values the group wrote, so Route 53 refuses it; as for any
            // other replaced resource, the update still completes and says what it could not delete.
            updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.2")));
            CfnStackWaits.StackState moved = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_COMPLETE", moved.status(), moved.reason());
            assertTrue(moved.reason().contains("could not be deleted during update cleanup"), moved.reason());
            assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.9"), "the changed record was deleted");
            assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"), "new zone lacks the record");

            // The given-up record no longer holds the group back.
            updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.3")));
            CfnStackWaits.StackState next = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_COMPLETE", next.status(), next.reason());
            assertTrue(listResourceRecordSets(newZone).contains("192.0.2.3"));
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        assertFalse(listResourceRecordSets(newZone).contains(name));
        assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.9"), "the stack delete took the changed record");
        changeRecordSet(oldZone, "DELETE", name, "192.0.2.9");
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @Test
    void recordGroupThatCollidesWritesNothingAndLeavesTheOtherOwnersRecord() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-taken-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String zoneId = createHostedZone(zoneName, "cfn-group-taken-" + suffix);
        String taken = "taken." + zoneName;
        changeRecordSet(zoneId, "CREATE", taken, "192.0.2.9");
        try {
            createStack(stackName, groupTemplate(zoneId,
                    groupRecord("new." + zoneName, "192.0.2.1") + "," + groupRecord(taken, "192.0.2.2")));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("ROLLBACK_COMPLETE", state.status(), state.reason());
            String failure = CfnStackWaits.failedResourceReason(stackName);
            assertTrue(failure.contains("already exists"), failure);
            String records = listResourceRecordSets(zoneId);
            assertFalse(records.contains("new." + zoneName), "a partial group was written: " + records);
            assertTrue(records.contains("192.0.2.9"), records);
            assertFalse(records.contains("192.0.2.2"), records);
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        changeRecordSet(zoneId, "DELETE", taken, "192.0.2.9");
        deleteHostedZone(zoneId);
    }

    @Test
    void recordGroupDeleteFailsOnARecordChangedOutsideTheStackUntilItIsPutBack() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-drift-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String zoneId = createHostedZone(zoneName, "cfn-group-drift-" + suffix);
        String name = "www." + zoneName;
        createStack(stackName, groupTemplate(zoneId, groupRecord(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        changeRecordSet(zoneId, "UPSERT", name, "192.0.2.2");

        // As on AWS, the DELETE names the values the stack wrote, so a changed record fails it.
        deleteStack(stackName);
        CfnStackWaits.StackState failed = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("DELETE_FAILED", failed.status(), failed.reason());
        assertTrue(listResourceRecordSets(zoneId).contains("192.0.2.2"), "the changed record was deleted");

        changeRecordSet(zoneId, "UPSERT", name, "192.0.2.1");
        deleteStack(stackName);
        CfnStackWaits.awaitStackDeleted(stackName);
        assertFalse(listResourceRecordSets(zoneId).contains(name));
        deleteHostedZone(zoneId);
    }

    @Test
    void recordGroupMoveWhoseRollbackCannotDeleteTheNewRecordsIsFinishedByContinueUpdateRollback() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-retry-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-retry-old-" + suffix);
        String newZone = createHostedZone(zoneName, "cfn-group-retry-new-" + suffix);
        String name = "www." + zoneName;
        createStack(stackName, groupTemplate(oldZone, groupRecord(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        try {
            refuseFirstGroupDeleteIn(newZone);
            updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.2"), "", FAILS_AFTER_GROUP));
            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stackName).status());
            assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"), "the old record was touched");
            assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"), "the refused delete went through");

            continueUpdateRollback(stackName);
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_ROLLBACK_COMPLETE", state.status(), state.reason());
            assertFalse(listResourceRecordSets(newZone).contains(name), "the retry left the moved record");
            assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"), "the retry touched the old record");
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        assertFalse(listResourceRecordSets(oldZone).contains(name));
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @Test
    void skippingAFailedRecordGroupRollbackKeepsTheOldRecordsAndLeavesTheNewOnesInTheirZone() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-skip-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-skip-old-" + suffix);
        String newZone = createHostedZone(zoneName, "cfn-group-skip-new-" + suffix);
        String name = "www." + zoneName;
        String taken = "taken." + zoneName;
        String restoredTemplate = groupTemplate(oldZone, groupRecord(name, "192.0.2.1"));
        createStack(stackName, restoredTemplate);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        changeRecordSet(oldZone, "CREATE", taken, "192.0.2.9");
        try {
            refuseFirstGroupDeleteIn(newZone);
            updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.2"), "", FAILS_AFTER_GROUP));
            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stackName).status());
            continueUpdateRollback(stackName, "Group");
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            // The skipped group names the old zone's record again, as the restored template does, so
            // an update of it that fails puts that record back rather than deleting it.
            updateStack(stackName, groupTemplate(oldZone,
                    groupRecord(name, "192.0.2.1") + "," + groupRecord(taken, "192.0.2.3")));
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
            assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"), "the old record was deleted");

            // An update that leaves the group alone commits. As on AWS, the record the skipped rollback
            // could not delete stays where it is: nothing tells it apart from one written there later.
            updateStack(stackName, restoredTemplate.replace("\"Resources\": {", """
                    "Resources": {
                      "Marker": {"Type": "AWS::SSM::Parameter",
                                 "Properties": {"Name": "/r53-group-skip/%s", "Type": "String", "Value": "kept"}},
                    """.formatted(suffix)));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
            assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"), "the old record was deleted");
            assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"), "the skipped leftover was deleted");
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        assertFalse(listResourceRecordSets(oldZone).contains(name));
        assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"), "the stack delete took the skipped leftover");
        changeRecordSet(newZone, "DELETE", name, "192.0.2.2");
        changeRecordSet(oldZone, "DELETE", taken, "192.0.2.9");
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @Test
    void reApplyingAMoveAfterASkippedRollbackKeepsTheRecordsItWrites() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-reapply-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-reapply-old-" + suffix);
        String newZone = createHostedZone(zoneName, "cfn-group-reapply-new-" + suffix);
        String name = "www." + zoneName;
        createStack(stackName, groupTemplate(oldZone, groupRecord(name, "192.0.2.1")));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        try {
            refuseFirstGroupDeleteIn(newZone);
            updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.2"), "", FAILS_AFTER_GROUP));
            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stackName).status());
            continueUpdateRollback(stackName, "Group");
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            // The record the failed update left is deleted by hand, and the move is applied again.
            changeRecordSet(newZone, "DELETE", name, "192.0.2.2");
            updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.2")));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
            assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"), "the cleanup deleted the moved record");
            assertFalse(listResourceRecordSets(oldZone).contains(name), "the old record was left behind");
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        assertFalse(listResourceRecordSets(newZone).contains(name));
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @Test
    void aRecordSetThatRewritesWhatASkippedGroupRollbackLeftKeepsIt() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-sibling-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-sibling-old-" + suffix);
        String newZone = createHostedZone(zoneName, "cfn-group-sibling-new-" + suffix);
        String name = "www." + zoneName;
        String restoredTemplate = groupTemplate(oldZone, groupRecord(name, "192.0.2.1"));
        createStack(stackName, restoredTemplate);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        try {
            refuseFirstGroupDeleteIn(newZone);
            updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.2"), "", FAILS_AFTER_GROUP));
            assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stackName).status());
            continueUpdateRollback(stackName, "Group");
            assertEquals("UPDATE_ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());

            // The leftover is deleted by hand, and a record set of the same stack writes the same record.
            changeRecordSet(newZone, "DELETE", name, "192.0.2.2");
            updateStack(stackName, restoredTemplate.replace("\"Resources\": {", """
                    "Resources": {
                      "Sibling": {"Type": "AWS::Route53::RecordSet",
                                  "Properties": {"HostedZoneId": "%s", "Name": "%s", "Type": "A", "TTL": "300",
                                                 "ResourceRecords": ["192.0.2.2"]}},
                    """.formatted(newZone, name)));
            CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
            assertEquals("UPDATE_COMPLETE", state.status(), state.reason());
            assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"),
                    "the group's cleanup deleted the record set's record");
        } finally {
            deleteStack(stackName);
            CfnStackWaits.awaitStackDeleted(stackName);
        }
        assertFalse(listResourceRecordSets(newZone).contains(name));
        assertFalse(listResourceRecordSets(oldZone).contains(name));
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    @Test
    void deletingAStackWhoseRecordGroupMoveRollbackFailedDeletesBothZonesRecordsEvenUnderRetain() {
        String suffix = Long.toString(System.nanoTime(), 36);
        String stackName = "r53-group-failed-delete-" + suffix;
        String zoneName = suffix + ".cfn-group-it.example.com";
        String oldZone = createHostedZone(zoneName, "cfn-group-failed-delete-old-" + suffix);
        String newZone = createHostedZone(zoneName, "cfn-group-failed-delete-new-" + suffix);
        String name = "www." + zoneName;
        String retain = ", \"UpdateReplacePolicy\": \"Retain\"";
        createStack(stackName, groupTemplate(oldZone, groupRecord(name, "192.0.2.1"), retain, ""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
        refuseFirstGroupDeleteIn(newZone);
        updateStack(stackName, groupTemplate(newZone, groupRecord(name, "192.0.2.2"), retain, FAILS_AFTER_GROUP));
        assertEquals("UPDATE_ROLLBACK_FAILED", CfnStackWaits.awaitTerminal(stackName).status());
        assertTrue(listResourceRecordSets(oldZone).contains("192.0.2.1"), "the old record was touched");
        assertTrue(listResourceRecordSets(newZone).contains("192.0.2.2"), "the refused delete went through");

        // Retain keeps what a committed replacement displaced; this move never committed.
        deleteStack(stackName);
        CfnStackWaits.awaitStackDeleted(stackName);
        assertFalse(listResourceRecordSets(oldZone).contains(name), "the group's own record outlived the stack");
        assertFalse(listResourceRecordSets(newZone).contains(name), "the failed update's record outlived the stack");
        deleteHostedZone(oldZone);
        deleteHostedZone(newZone);
    }

    private static String groupRecord(String name, String address) {
        return """
                {"Name": "%s", "Type": "A", "TTL": "300", "ResourceRecords": ["%s"]}""".formatted(name, address);
    }

    private static String groupTemplate(String zoneId, String recordSets) {
        return groupTemplate(zoneId, recordSets, "", "");
    }

    /**
     * One record group in {@code zoneId}, with outputs for its {@code Ref} and {@code Fn::GetAtt Id}.
     * {@code policy} is added to the group's definition and {@code extra} after it, each led by a
     * comma.
     */
    private static String groupTemplate(String zoneId, String recordSets, String policy, String extra) {
        return """
                {
                  "Resources": {
                    "Group": {
                      "Type": "AWS::Route53::RecordSetGroup"%s,
                      "Properties": {"HostedZoneId": "%s", "RecordSets": [%s]}
                    }%s
                  },
                  "Outputs": {
                    "GroupRef": {"Value": {"Ref": "Group"}},
                    "GroupChange": {"Value": {"Fn::GetAtt": ["Group", "Id"]}}
                  }
                }
                """.formatted(policy, zoneId, recordSets, extra);
    }

    private void parameterizedStackRequest(String stackName, String action, String template, String parameter,
                                           String value) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
            .formParam("Parameters.member.1.ParameterKey", parameter)
            .formParam("Parameters.member.1.ParameterValue", value)
        .when().post("/").then().statusCode(200);
    }

    private RequestSpecification changeSetRequest(String action, String stackName, String changeSetName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("ChangeSetName", changeSetName);
    }

    private String stackOutput(String stackName, String key) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when().post("/").then().statusCode(200)
            .extract().xmlPath().getString("DescribeStacksResponse.DescribeStacksResult.Stacks.member.Outputs.member"
                    + ".find { it.OutputKey == '" + key + "' }.OutputValue");
    }

    private void assertChangeInSync(String changeId) {
        given()
            .header("Authorization", ROUTE53_AUTH)
        .when().get("/2013-04-01/change/" + changeId.substring(changeId.lastIndexOf('/') + 1))
        .then().statusCode(200)
            .body(containsString("<Status>INSYNC</Status>"));
    }

    private void deleteHostedZone(String zoneId) {
        given()
            .header("Authorization", ROUTE53_AUTH)
        .when().delete("/2013-04-01/hostedzone/" + zoneId)
        .then().statusCode(200);
    }

    /**
     * Refuses the first DELETE batch the group sends to {@code zoneId}, as Route 53 refuses one whose
     * values no longer match a record changed outside the stack; later batches go through.
     */
    private void refuseFirstGroupDeleteIn(String zoneId) {
        Mockito.doThrow(new AwsException("InvalidChangeBatch",
                        "Tried to delete resource record set but the values provided do not match the current values",
                        400))
                .doCallRealMethod()
                .when(route53Service).changeResourceRecordSets(eq(zoneId), anyList(), eq("CloudFormation delete Group"));
    }

    private void continueUpdateRollback(String stackName, String... resourcesToSkip) {
        RequestSpecification request = given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "ContinueUpdateRollback")
            .formParam("StackName", stackName);
        for (int i = 0; i < resourcesToSkip.length; i++) {
            request.formParam("ResourcesToSkip.member." + (i + 1), resourcesToSkip[i]);
        }
        request.when().post("/").then().statusCode(200);
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
