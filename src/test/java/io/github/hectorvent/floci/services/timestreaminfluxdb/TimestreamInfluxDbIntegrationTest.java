package io.github.hectorvent.floci.services.timestreaminfluxdb;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@QuarkusTest
class TimestreamInfluxDbIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.0";
    private static final String TARGET = "AmazonTimestreamInfluxDB.";
    private static final String ACCOUNT = "000000000000";
    private static final String ID_PATTERN = "[a-z0-9]{10}";

    @BeforeAll
    static void setup() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void instanceLifecycleReportsTransitionalStatusesAndSimulatedEndpoint() {
        String id = createInstance("lifecycle-db", "")
                .statusCode(200)
                .body("id", matchesPattern(ID_PATTERN))
                .body("name", equalTo("lifecycle-db"))
                .body("status", equalTo("CREATING"))
                .body("port", equalTo(8086))
                .body("deploymentType", equalTo("SINGLE_AZ"))
                .body("networkType", equalTo("IPV4"))
                .body("dbStorageType", equalTo("InfluxIOIncludedT1"))
                .body("publiclyAccessible", equalTo(false))
                .body("availabilityZone", equalTo("us-east-1a"))
                .body("vpcSubnetIds", contains("subnet-abc123"))
                .body("vpcSecurityGroupIds", contains("sg-abc123"))
                .body("influxAuthParametersSecretArn", nullValue())
                .extract().path("id");

        call("GetDbInstance", "{\"identifier\":\"" + id + "\"}")
                .statusCode(200)
                .body("status", equalTo("AVAILABLE"))
                .body("arn", equalTo("arn:aws:timestream-influxdb:us-east-1:" + ACCOUNT + ":db-instance/" + id))
                .body("endpoint", equalTo("localhost"))
                .body("influxAuthParametersSecretArn", startsWith("arn:aws:secretsmanager:us-east-1:" + ACCOUNT + ":secret:"));

        call("ListDbInstances", "{}")
                .statusCode(200)
                .body("items.id", hasItem(id));

        call("UpdateDbInstance", """
                {"identifier":"%s","dbInstanceType":"db.influx.xlarge","port":9000}
                """.formatted(id))
                .statusCode(200)
                .body("status", equalTo("UPDATING_INSTANCE_TYPE"))
                .body("dbInstanceType", equalTo("db.influx.xlarge"))
                .body("port", equalTo(9000));

        call("RebootDbInstance", "{\"identifier\":\"" + id + "\"}")
                .statusCode(200)
                .body("status", equalTo("REBOOTING"));

        call("GetDbInstance", "{\"identifier\":\"" + id + "\"}")
                .statusCode(200)
                .body("status", equalTo("AVAILABLE"))
                .body("port", equalTo(9000));

        call("DeleteDbInstance", "{\"identifier\":\"" + id + "\"}")
                .statusCode(200)
                .body("status", equalTo("DELETING"));

        call("GetDbInstance", "{\"identifier\":\"" + id + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceId", equalTo(id))
                .body("resourceType", equalTo("DB_INSTANCE"));
    }

    @Test
    void createInstanceStoresInitialAuthParametersInSecretsManager() {
        String id = createInstance("secret-db", ",\"username\":\"operator\",\"organization\":\"acme\",\"bucket\":\"metrics\"")
                .statusCode(200)
                .extract().path("id");
        String secretArn = call("GetDbInstance", "{\"identifier\":\"" + id + "\"}")
                .statusCode(200)
                .extract().path("influxAuthParametersSecretArn");

        String secret = given().contentType("application/x-amz-json-1.1")
                .header("X-Amz-Target", "secretsmanager.GetSecretValue")
                .body("{\"SecretId\":\"" + secretArn + "\"}")
                .when().post("/")
                .then().statusCode(200)
                .extract().path("SecretString");

        assertEquals("{\"organization\":\"acme\",\"bucket\":\"metrics\",\"username\":\"operator\",\"password\":\"password123\"}",
                secret);
    }

    @Test
    void createInstanceRejectsInputOutsideTheModelConstraints() {
        call("CreateDbInstance", """
                {"name":"no-password","dbInstanceType":"db.influx.medium","allocatedStorage":20,
                 "vpcSubnetIds":["subnet-abc123"],"vpcSecurityGroupIds":["sg-abc123"]}
                """)
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("reason", equalTo("FIELD_VALIDATION_FAILED"));

        assertValidation(instanceBody("1bad", "db.influx.medium", 20, "8086"));
        assertValidation(instanceBody("bad-storage", "db.influx.medium", 19, "8086"));
        assertValidation(instanceBody("bad-type", "db.influx.tiny", 20, "8086"));
        assertValidation(instanceBody("reserved-port", "db.influx.medium", 20, "8090"));
        assertValidation("""
                {"name":"no-subnets","password":"password123","dbInstanceType":"db.influx.medium","allocatedStorage":20,
                 "vpcSubnetIds":[],"vpcSecurityGroupIds":["sg-abc123"]}
                """);
    }

    @Test
    void instanceNamesAreUniquePerAccountAndRegion() {
        String id = createInstance("dup-db", "").statusCode(200).extract().path("id");

        createInstance("dup-db", "")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"))
                .body("resourceId", equalTo(id))
                .body("resourceType", equalTo("DB_INSTANCE"));
    }

    @Test
    void listDbParameterGroupsPaginatesWithMaxResultsAndNextToken() {
        for (int i = 0; i < 3; i++) {
            call("CreateDbParameterGroup", "{\"name\":\"page-group-" + i + "\"}").statusCode(200);
        }
        int total = call("ListDbParameterGroups", "{}").statusCode(200).extract().path("items.size()");

        String token = call("ListDbParameterGroups", "{\"maxResults\":2}")
                .statusCode(200)
                .body("items", hasSize(2))
                .body("nextToken", notNullValue())
                .extract().path("nextToken");

        call("ListDbParameterGroups", "{\"maxResults\":100,\"nextToken\":\"" + token + "\"}")
                .statusCode(200)
                .body("items", hasSize(total - 2))
                .body("nextToken", nullValue());

        assertValidationFor("ListDbParameterGroups", "{\"maxResults\":0}");
        assertValidationFor("ListDbParameterGroups", "{\"maxResults\":101}");
    }

    @Test
    void parameterGroupsAreRetrievableAndReferencedByInstances() {
        String groupId = call("CreateDbParameterGroup", """
                {"name":"tuned-v2","description":"tuned","parameters":{"InfluxDBv2":{"logLevel":"debug","queryConcurrency":10}},
                 "tags":{"team":"metrics"}}
                """)
                .statusCode(200)
                .body("id", matchesPattern(ID_PATTERN))
                .body("arn", endsWithResource("db-parameter-group"))
                .body("parameters.InfluxDBv2.logLevel", equalTo("debug"))
                .extract().path("id");

        call("GetDbParameterGroup", "{\"identifier\":\"" + groupId + "\"}")
                .statusCode(200)
                .body("name", equalTo("tuned-v2"))
                .body("description", equalTo("tuned"))
                .body("parameters.InfluxDBv2.queryConcurrency", equalTo(10));

        call("CreateDbParameterGroup", "{\"name\":\"tuned-v2\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"))
                .body("resourceType", equalTo("DB_PARAMETER_GROUP"));

        assertValidationFor("CreateDbParameterGroup",
                "{\"name\":\"bad-level\",\"parameters\":{\"InfluxDBv2\":{\"logLevel\":\"verbose\"}}}");

        createInstance("grouped-db", ",\"dbParameterGroupIdentifier\":\"" + groupId + "\"")
                .statusCode(200)
                .body("dbParameterGroupIdentifier", equalTo(groupId));

        createInstance("missing-group-db", ",\"dbParameterGroupIdentifier\":\"nosuchgroup\"")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("DB_PARAMETER_GROUP"));
    }

    @Test
    void clusterLifecycleManagesItsMemberInstances() {
        String clusterId = call("CreateDbCluster", """
                {"name":"lifecycle-cluster","password":"password123","dbInstanceType":"db.influx.large",
                 "allocatedStorage":40,"vpcSubnetIds":["subnet-abc123","subnet-def456"],"vpcSecurityGroupIds":["sg-abc123"]}
                """)
                .statusCode(200)
                .body("dbClusterId", matchesPattern(ID_PATTERN))
                .body("dbClusterStatus", equalTo("CREATING"))
                .extract().path("dbClusterId");

        call("GetDbCluster", "{\"dbClusterId\":\"" + clusterId + "\"}")
                .statusCode(200)
                .body("status", equalTo("AVAILABLE"))
                .body("arn", equalTo("arn:aws:timestream-influxdb:us-east-1:" + ACCOUNT + ":db-cluster/" + clusterId))
                .body("endpoint", equalTo("localhost"))
                .body("readerEndpoint", equalTo("localhost"))
                .body("engineType", equalTo("INFLUXDB_V2"))
                .body("deploymentType", equalTo("MULTI_NODE_READ_REPLICAS"))
                .body("failoverMode", equalTo("AUTOMATIC"))
                .body("port", equalTo(8086));

        call("ListDbClusters", "{}")
                .statusCode(200)
                .body("items.id", hasItem(clusterId));

        ValidatableResponse members = call("ListDbInstancesForCluster", "{\"dbClusterId\":\"" + clusterId + "\"}")
                .statusCode(200)
                .body("items", hasSize(2))
                .body("items.instanceMode", containsInAnyOrder("PRIMARY", "REPLICA"))
                .body("items.dbInstanceType", contains("db.influx.large", "db.influx.large"));
        List<String> memberIds = members.extract().path("items.id");

        call("GetDbInstance", "{\"identifier\":\"" + memberIds.get(0) + "\"}")
                .statusCode(200)
                .body("dbClusterId", equalTo(clusterId));

        call("DeleteDbInstance", "{\"identifier\":\"" + memberIds.get(0) + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));

        call("UpdateDbCluster", "{\"dbClusterId\":\"" + clusterId + "\",\"dbInstanceType\":\"db.influx.2xlarge\"}")
                .statusCode(200)
                .body("dbClusterStatus", equalTo("UPDATING_INSTANCE_TYPE"));

        call("ListDbInstancesForCluster", "{\"dbClusterId\":\"" + clusterId + "\"}")
                .statusCode(200)
                .body("items.dbInstanceType", contains("db.influx.2xlarge", "db.influx.2xlarge"));

        call("RebootDbCluster", "{\"dbClusterId\":\"" + clusterId + "\",\"instanceIds\":[\"" + memberIds.get(1) + "\"]}")
                .statusCode(200)
                .body("dbClusterStatus", equalTo("REBOOTING"));

        call("RebootDbCluster", "{\"dbClusterId\":\"" + clusterId + "\",\"instanceIds\":[\"notamember\"]}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("DB_INSTANCE"));

        call("DeleteDbCluster", "{\"dbClusterId\":\"" + clusterId + "\"}")
                .statusCode(200)
                .body("dbClusterStatus", equalTo("DELETING"));

        call("ListDbInstancesForCluster", "{\"dbClusterId\":\"" + clusterId + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("DB_CLUSTER"));

        call("ListDbInstances", "{}")
                .statusCode(200)
                .body("items.id", not(hasItem(memberIds.get(0))));
    }

    @Test
    void enterpriseClusterIsSizedFromItsParameterGroup() {
        String groupId = call("CreateDbParameterGroup", """
                {"name":"enterprise-group","parameters":{"InfluxDBv3Enterprise":
                  {"ingestQueryInstances":2,"queryOnlyInstances":1,"dedicatedCompactor":true}}}
                """)
                .statusCode(200)
                .extract().path("id");

        String clusterId = call("CreateDbCluster", """
                {"name":"enterprise-cluster","dbInstanceType":"db.influx.large","dbParameterGroupIdentifier":"%s",
                 "vpcSubnetIds":["subnet-abc123"],"vpcSecurityGroupIds":["sg-abc123"]}
                """.formatted(groupId))
                .statusCode(200)
                .extract().path("dbClusterId");

        call("GetDbCluster", "{\"dbClusterId\":\"" + clusterId + "\"}")
                .statusCode(200)
                .body("engineType", equalTo("INFLUXDB_V3_ENTERPRISE"))
                .body("port", equalTo(8181))
                .body("dbParameterGroupIdentifier", equalTo(groupId))
                .body("clusterConfiguration.ingestQueryInstances", equalTo(2))
                .body("clusterConfiguration.queryOnlyInstances", equalTo(1))
                .body("clusterConfiguration.dedicatedCompactor", equalTo(true));

        call("ListDbInstancesForCluster", "{\"dbClusterId\":\"" + clusterId + "\"}")
                .statusCode(200)
                .body("items", hasSize(4));

        assertValidationFor("CreateDbParameterGroup",
                "{\"name\":\"enterprise-missing\",\"parameters\":{\"InfluxDBv3Enterprise\":{\"queryOnlyInstances\":1}}}");

        createInstance("v3-standalone", ",\"dbParameterGroupIdentifier\":\"" + groupId + "\"")
                .statusCode(400)
                .body("__type", equalTo("ValidationException"));
    }

    @Test
    void tagsCanBeAddedListedAndRemovedByArn() {
        String arn = createInstance("tagged-db", ",\"tags\":{\"env\":\"dev\"}")
                .statusCode(200)
                .extract().path("arn");

        call("TagResource", "{\"resourceArn\":\"" + arn + "\",\"tags\":{\"team\":\"metrics\"}}")
                .statusCode(200);

        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.env", equalTo("dev"))
                .body("tags.team", equalTo("metrics"));

        call("UntagResource", "{\"resourceArn\":\"" + arn + "\",\"tagKeys\":[\"env\"]}")
                .statusCode(200);

        call("ListTagsForResource", "{\"resourceArn\":\"" + arn + "\"}")
                .statusCode(200)
                .body("tags.env", nullValue())
                .body("tags.team", equalTo("metrics"));

        call("ListTagsForResource",
                "{\"resourceArn\":\"arn:aws:timestream-influxdb:us-east-1:" + ACCOUNT + ":db-instance/missing123\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        assertValidationFor("ListTagsForResource", "{\"resourceArn\":\"arn:aws:s3:::bucket\"}");
    }

    @Test
    void backupsCanBeTakenRestoredAndDeleted() {
        String instanceId = createInstance("backup-source", "").statusCode(200).extract().path("id");

        String backupId = call("CreateDbBackup", """
                {"name":"nightly","dbResourceId":"%s","retentionDays":7}
                """.formatted(instanceId))
                .statusCode(200)
                .body("id", matchesPattern(ID_PATTERN))
                .body("arn", endsWithResource("db-backup"))
                .body("status", equalTo("IN_PROGRESS"))
                .body("type", equalTo("ON_DEMAND"))
                .body("dbResourceId", equalTo(instanceId))
                .body("engineType", equalTo("INFLUXDB_V2"))
                .body("createdAt", instanceOf(Number.class))
                .body("expiresAfter", matchesPattern("\\d{4}-\\d{2}-\\d{2}"))
                .extract().path("id");

        call("GetDbBackup", "{\"identifier\":\"" + backupId + "\"}")
                .statusCode(200)
                .body("status", equalTo("COMPLETED"))
                .body("dbInstanceType", equalTo("db.influx.medium"))
                .body("allocatedStorage", equalTo(20));

        call("ListDbBackups", "{\"dbResourceId\":\"" + instanceId + "\"}")
                .statusCode(200)
                .body("items.id", contains(backupId));

        call("CreateDbBackup", "{\"name\":\"nightly\",\"dbResourceId\":\"" + instanceId + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));

        call("CreateDbBackup", "{\"name\":\"orphan\",\"dbResourceId\":\"missing123\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"));

        String restoredId = call("RestoreFromDbBackup", "{\"name\":\"restored-db\",\"dbBackupId\":\"" + backupId + "\"}")
                .statusCode(200)
                .body("restoreStatus", equalTo("RESTORING"))
                .body("resourceType", equalTo("DB_INSTANCE"))
                .body("engineType", equalTo("INFLUXDB_V2"))
                .body("deploymentType", equalTo("SINGLE_AZ"))
                .extract().path("restoredDbResourceId");
        assertNotEquals(instanceId, restoredId);

        call("GetDbInstance", "{\"identifier\":\"" + restoredId + "\"}")
                .statusCode(200)
                .body("name", equalTo("restored-db"))
                .body("status", equalTo("AVAILABLE"))
                .body("dbInstanceType", equalTo("db.influx.medium"));

        call("RestoreFromDbBackup", "{\"name\":\"backup-source\",\"dbBackupId\":\"" + backupId + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ConflictException"));

        assertValidationFor("RestoreFromDbBackup",
                "{\"name\":\"other-name\",\"dbBackupId\":\"" + backupId + "\",\"restoreMode\":\"REPLACE_EXISTING\"}");

        call("RestoreFromDbBackup",
                "{\"name\":\"backup-source\",\"dbBackupId\":\"" + backupId + "\",\"restoreMode\":\"REPLACE_EXISTING\"}")
                .statusCode(200)
                .body("restoredDbResourceId", equalTo(instanceId));

        call("DeleteDbBackup", "{\"identifier\":\"" + backupId + "\"}")
                .statusCode(200)
                .body("status", equalTo("DELETING"));

        call("GetDbBackup", "{\"identifier\":\"" + backupId + "\"}")
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("DB_BACKUP"));
    }

    @Test
    void pointInTimeRestoreIsRejectedAndLeavesNothingBehind() {
        String instanceId = createInstance("pitr-source", "").statusCode(200).extract().path("id");
        String backupId = call("CreateDbBackup", "{\"name\":\"pitr-snap\",\"dbResourceId\":\"" + instanceId + "\"}")
                .statusCode(200).extract().path("id");

        assertValidationFor("RestoreFromDbBackup",
                "{\"name\":\"pitr-restored\",\"dbBackupId\":\"" + backupId + "\",\"restoreToTime\":1700000000}");

        call("ListDbInstances", "{\"maxResults\":100}")
                .statusCode(200)
                .body("items.name", not(hasItem("pitr-restored")));
    }

    @Test
    void replaceExistingRejectsConfigurationOverrides() {
        String instanceId = createInstance("replace-source", "").statusCode(200).extract().path("id");
        String backupId = call("CreateDbBackup", "{\"name\":\"replace-snap\",\"dbResourceId\":\"" + instanceId + "\"}")
                .statusCode(200).extract().path("id");
        String replaceBase = "{\"name\":\"replace-source\",\"dbBackupId\":\"" + backupId
                + "\",\"restoreMode\":\"REPLACE_EXISTING\"";

        assertValidationFor("RestoreFromDbBackup", replaceBase + ",\"port\":9100}");
        assertValidationFor("RestoreFromDbBackup", replaceBase + ",\"tags\":{\"env\":\"dev\"}}");
        assertValidationFor("RestoreFromDbBackup", replaceBase + ",\"networkType\":\"DUAL\"}");
        assertValidationFor("RestoreFromDbBackup", replaceBase + ",\"vpcSubnetIds\":[\"subnet-zzz999\"]}");
        assertValidationFor("RestoreFromDbBackup", replaceBase + ",\"publiclyAccessible\":true}");
        assertValidationFor("RestoreFromDbBackup", replaceBase + ",\"deploymentType\":\"WITH_MULTIAZ_STANDBY\"}");
        assertValidationFor("RestoreFromDbBackup", replaceBase + ",\"kmsKeyId\":\"alias/floci\"}");

        call("GetDbInstance", "{\"identifier\":\"" + instanceId + "\"}")
                .statusCode(200)
                .body("port", equalTo(8086))
                .body("networkType", equalTo("IPV4"))
                .body("deploymentType", equalTo("SINGLE_AZ"))
                .body("publiclyAccessible", equalTo(false))
                .body("vpcSubnetIds", contains("subnet-abc123"));

        call("RestoreFromDbBackup", replaceBase + "}")
                .statusCode(200)
                .body("restoredDbResourceId", equalTo(instanceId));
    }

    private static org.hamcrest.Matcher<String> endsWithResource(String resourceType) {
        return matchesPattern("arn:aws:timestream-influxdb:us-east-1:" + ACCOUNT + ":" + resourceType + "/" + ID_PATTERN);
    }

    private static ValidatableResponse createInstance(String name, String extraFields) {
        return call("CreateDbInstance", """
                {"name":"%s","password":"password123","dbInstanceType":"db.influx.medium","allocatedStorage":20,
                 "vpcSubnetIds":["subnet-abc123"],"vpcSecurityGroupIds":["sg-abc123"]%s}
                """.formatted(name, extraFields));
    }

    private static String instanceBody(String name, String instanceType, int storage, String port) {
        return """
                {"name":"%s","password":"password123","dbInstanceType":"%s","allocatedStorage":%d,"port":%s,
                 "vpcSubnetIds":["subnet-abc123"],"vpcSecurityGroupIds":["sg-abc123"]}
                """.formatted(name, instanceType, storage, port);
    }

    private static void assertValidation(String body) {
        assertValidationFor("CreateDbInstance", body);
    }

    private static void assertValidationFor(String action, String body) {
        call(action, body)
                .statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("reason", equalTo("FIELD_VALIDATION_FAILED"));
    }

    private static ValidatableResponse call(String action, String body) {
        return given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET + action)
                .body(body)
                .when().post("/")
                .then();
    }
}
