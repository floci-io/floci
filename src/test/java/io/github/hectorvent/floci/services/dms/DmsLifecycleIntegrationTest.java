package io.github.hectorvent.floci.services.dms;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * Endpoint, replication instance and replication task lifecycles, driven the way the Terraform
 * provider drives them: every mutation is read back through a separate describe call rather than
 * trusted from the mutating call's own response.
 */
@QuarkusTest
class DmsLifecycleIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final String TARGET_PREFIX = "AmazonDMSv20160101.";
    private static final String ACCOUNT_ID = "723679240095";
    private static final String AUTH_HEADER =
            "AWS4-HMAC-SHA256 Credential=" + ACCOUNT_ID + "/20260101/us-east-1/dms/aws4_request";
    private static final String SUBNET_A = "subnet-default-us-east-1-a";
    private static final String SUBNET_B = "subnet-default-us-east-1-b";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void endpointLifecycleIsReadableThroughDescribe() {
        String arn = createEndpoint("lc-ep-source", "source",
                ",\"Username\":\"admin\",\"Password\":\"hunter2\",\"ServerName\":\"db.example\","
                        + "\"Port\":3306,\"DatabaseName\":\"app\","
                        + "\"Tags\":[{\"Key\":\"env\",\"Value\":\"test\"}]");

        describeEndpoint("lc-ep-source")
                .body("Endpoints", hasSize(1))
                .body("Endpoints[0].EndpointArn", equalTo(arn))
                .body("Endpoints[0].EndpointType", equalTo("SOURCE"))
                .body("Endpoints[0].EngineName", equalTo("mysql"))
                .body("Endpoints[0].Status", equalTo("active"))
                .body("Endpoints[0].Username", equalTo("admin"))
                .body("Endpoints[0].ServerName", equalTo("db.example"))
                .body("Endpoints[0].Port", equalTo(3306))
                .body("Endpoints[0].DatabaseName", equalTo("app"))
                .body("Endpoints[0].SslMode", equalTo("none"))
                .body("Endpoints[0].Password", nullValue());

        dms("DescribeEndpoints")
                .body("{\"Filters\":[{\"Name\":\"endpoint-arn\",\"Values\":[\"" + arn + "\"]},"
                        + "{\"Name\":\"endpoint-type\",\"Values\":[\"source\"]}]}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Endpoints[0].EndpointIdentifier", equalTo("lc-ep-source"));

        dms("ModifyEndpoint")
                .body("{\"EndpointArn\":\"" + arn + "\",\"Port\":3307,\"SslMode\":\"require\"}")
        .when().post("/")
        .then().statusCode(200);

        describeEndpoint("lc-ep-source")
                .body("Endpoints[0].Port", equalTo(3307))
                .body("Endpoints[0].SslMode", equalTo("require"))
                .body("Endpoints[0].ServerName", equalTo("db.example"));

        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("TagList", hasSize(1))
                .body("TagList[0].Key", equalTo("env"));

        dms("DeleteEndpoint")
                .body("{\"EndpointArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Endpoint.Status", equalTo("deleting"));

        dms("DescribeEndpoints")
                .body("{\"Filters\":[{\"Name\":\"endpoint-id\",\"Values\":[\"lc-ep-source\"]}]}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void endpointSettingsMergeOnModifyAndNeverReturnSecrets() {
        String arn = createEndpoint("lc-ep-s3", "target",
                ",\"S3Settings\":{\"BucketName\":\"b1\",\"ServiceAccessRoleArn\":\"arn:aws:iam::"
                        + ACCOUNT_ID + ":role/dms\",\"CsvDelimiter\":\",\"},"
                        + "\"MySQLSettings\":{\"ServerName\":\"x\",\"Password\":\"secret\"}");

        dms("ModifyEndpoint")
                .body("{\"EndpointArn\":\"" + arn + "\",\"S3Settings\":{\"BucketFolder\":\"cdc\"}}")
        .when().post("/")
        .then().statusCode(200);

        describeEndpoint("lc-ep-s3")
                .body("Endpoints[0].EndpointType", equalTo("TARGET"))
                .body("Endpoints[0].S3Settings.BucketName", equalTo("b1"))
                .body("Endpoints[0].S3Settings.BucketFolder", equalTo("cdc"))
                .body("Endpoints[0].S3Settings.CsvDelimiter", equalTo(","))
                .body("Endpoints[0].MySQLSettings.ServerName", equalTo("x"))
                .body("Endpoints[0].MySQLSettings.Password", nullValue());

        dms("ModifyEndpoint")
                .body("{\"EndpointArn\":\"" + arn + "\",\"ExactSettings\":true,"
                        + "\"S3Settings\":{\"BucketName\":\"b2\"}}")
        .when().post("/")
        .then().statusCode(200);

        describeEndpoint("lc-ep-s3")
                .body("Endpoints[0].S3Settings.BucketName", equalTo("b2"))
                .body("Endpoints[0].S3Settings.BucketFolder", nullValue());

        delete("DeleteEndpoint", "EndpointArn", arn);
    }

    @Test
    void duplicateEndpointIdentifierIsRejected() {
        String arn = createEndpoint("lc-ep-dup", "source", "");
        dms("CreateEndpoint")
                .body("{\"EndpointIdentifier\":\"lc-ep-dup\",\"EndpointType\":\"source\","
                        + "\"EngineName\":\"mysql\"}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceAlreadyExistsFault"));
        delete("DeleteEndpoint", "EndpointArn", arn);
    }

    @Test
    void replicationInstanceLifecycleIsReadableThroughDescribe() {
        dms("CreateReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"lc-rep-subnets\","
                        + "\"ReplicationSubnetGroupDescription\":\"lifecycle\","
                        + "\"SubnetIds\":[\"" + SUBNET_A + "\",\"" + SUBNET_B + "\"]}")
        .when().post("/")
        .then().statusCode(200);

        String arn = dms("CreateReplicationInstance")
                .body("{\"ReplicationInstanceIdentifier\":\"LC-Rep\","
                        + "\"ReplicationInstanceClass\":\"dms.t3.micro\",\"AllocatedStorage\":20,"
                        + "\"ReplicationSubnetGroupIdentifier\":\"lc-rep-subnets\","
                        + "\"PubliclyAccessible\":false,\"MultiAZ\":false,"
                        + "\"AutoMinorVersionUpgrade\":true,\"VpcSecurityGroupIds\":[\"sg-1\"],"
                        + "\"Tags\":[{\"Key\":\"team\",\"Value\":\"data\"}]}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ReplicationInstance.ReplicationInstanceArn",
                        matchesPattern("arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":rep:[A-Z2-7]{26}"))
                .extract().path("ReplicationInstance.ReplicationInstanceArn");

        describeInstance("lc-rep")
                .body("ReplicationInstances", hasSize(1))
                .body("ReplicationInstances[0].ReplicationInstanceArn", equalTo(arn))
                .body("ReplicationInstances[0].ReplicationInstanceIdentifier", equalTo("lc-rep"))
                .body("ReplicationInstances[0].ReplicationInstanceStatus", equalTo("available"))
                .body("ReplicationInstances[0].ReplicationInstanceClass", equalTo("dms.t3.micro"))
                .body("ReplicationInstances[0].AllocatedStorage", equalTo(20))
                .body("ReplicationInstances[0].PubliclyAccessible", equalTo(false))
                .body("ReplicationInstances[0].VpcSecurityGroups[0].VpcSecurityGroupId", equalTo("sg-1"))
                .body("ReplicationInstances[0].ReplicationSubnetGroup.ReplicationSubnetGroupIdentifier",
                        equalTo("lc-rep-subnets"))
                .body("ReplicationInstances[0].ReplicationSubnetGroup.VpcId", equalTo("vpc-default-us-east-1"))
                .body("ReplicationInstances[0].EngineVersion", notNullValue());

        dms("DeleteReplicationSubnetGroup")
                .body("{\"ReplicationSubnetGroupIdentifier\":\"lc-rep-subnets\"}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"));

        dms("ModifyReplicationInstance")
                .body("{\"ReplicationInstanceArn\":\"" + arn + "\",\"ApplyImmediately\":true,"
                        + "\"ReplicationInstanceClass\":\"dms.c5.large\",\"AllocatedStorage\":100}")
        .when().post("/")
        .then().statusCode(200);

        describeInstance("lc-rep")
                .body("ReplicationInstances[0].ReplicationInstanceClass", equalTo("dms.c5.large"))
                .body("ReplicationInstances[0].AllocatedStorage", equalTo(100))
                .body("ReplicationInstances[0].ReplicationInstanceStatus", equalTo("available"));

        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("TagList[0].Key", equalTo("team"));

        dms("DeleteReplicationInstance")
                .body("{\"ReplicationInstanceArn\":\"" + arn + "\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ReplicationInstance.ReplicationInstanceStatus", equalTo("deleting"));

        dms("DescribeReplicationInstances")
                .body("{\"Filters\":[{\"Name\":\"replication-instance-id\",\"Values\":[\"lc-rep\"]}]}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        delete("DeleteReplicationSubnetGroup", "ReplicationSubnetGroupIdentifier", "lc-rep-subnets");
    }

    @Test
    void replicationInstanceWithoutSubnetGroupUsesDefault() {
        String arn = createInstance("lc-rep-default");
        describeInstance("lc-rep-default")
                .body("ReplicationInstances[0].ReplicationSubnetGroup.ReplicationSubnetGroupIdentifier",
                        equalTo("default"))
                .body("ReplicationInstances[0].ReplicationSubnetGroup.VpcId", equalTo("vpc-default-us-east-1"))
                .body("ReplicationInstances[0].ReplicationSubnetGroup.SubnetGroupStatus", equalTo("Complete"))
                .body("ReplicationInstances[0].ReplicationSubnetGroup.Subnets.SubnetIdentifier",
                        hasItems(SUBNET_A, SUBNET_B));
        delete("DeleteReplicationInstance", "ReplicationInstanceArn", arn);
    }

    @Test
    void replicationInstanceStoresOnlyModeledKerberosMembers() {
        dms("CreateReplicationInstance")
                .body("{\"ReplicationInstanceIdentifier\":\"lc-rep-kerberos\","
                        + "\"ReplicationInstanceClass\":\"dms.t3.micro\","
                        + "\"KerberosAuthenticationSettings\":{\"KeyCacheSecretId\":\"krb-cache\","
                        + "\"Krb5FileContents\":\"[libdefaults]\",\"Password\":\"hunter2\"}}")
        .when().post("/")
        .then().statusCode(200);
        describeInstance("lc-rep-kerberos")
                .body("ReplicationInstances[0].KerberosAuthenticationSettings.KeyCacheSecretId",
                        equalTo("krb-cache"))
                .body("ReplicationInstances[0].KerberosAuthenticationSettings.Krb5FileContents",
                        equalTo("[libdefaults]"))
                .body("ReplicationInstances[0].KerberosAuthenticationSettings", not(hasKey("Password")));
        String arn = describeInstance("lc-rep-kerberos").extract()
                .path("ReplicationInstances[0].ReplicationInstanceArn");
        delete("DeleteReplicationInstance", "ReplicationInstanceArn", arn);
    }

    @Test
    void replicationInstanceWithUnknownSubnetGroupIsNotFound() {
        dms("CreateReplicationInstance")
                .body("{\"ReplicationInstanceIdentifier\":\"lc-rep-nogroup\","
                        + "\"ReplicationInstanceClass\":\"dms.t3.micro\","
                        + "\"ReplicationSubnetGroupIdentifier\":\"lc-never-created\"}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void replicationTaskLifecycleAcrossStartAndStop() {
        String source = createEndpoint("lc-task-src", "source", "");
        String target = createEndpoint("lc-task-tgt", "target", "");
        String instance = createInstance("lc-task-rep");

        String task = dms("CreateReplicationTask")
                .body("{\"ReplicationTaskIdentifier\":\"lc-task\",\"SourceEndpointArn\":\"" + source + "\","
                        + "\"TargetEndpointArn\":\"" + target + "\",\"ReplicationInstanceArn\":\"" + instance + "\","
                        + "\"MigrationType\":\"full-load\",\"TableMappings\":" + jsonString(mappings("%"))
                        + ",\"Tags\":[{\"Key\":\"k\",\"Value\":\"v\"}]}")
        .when().post("/")
        .then()
                .statusCode(200)
                .extract().path("ReplicationTask.ReplicationTaskArn");

        describeTask("lc-task")
                .body("ReplicationTasks", hasSize(1))
                .body("ReplicationTasks[0].ReplicationTaskArn", equalTo(task))
                .body("ReplicationTasks[0].Status", equalTo("ready"))
                .body("ReplicationTasks[0].SourceEndpointArn", equalTo(source))
                .body("ReplicationTasks[0].TargetEndpointArn", equalTo(target))
                .body("ReplicationTasks[0].ReplicationInstanceArn", equalTo(instance))
                .body("ReplicationTasks[0].MigrationType", equalTo("full-load"))
                .body("ReplicationTasks[0].TableMappings", equalTo(mappings("%")));

        dms("DescribeReplicationTasks")
                .body("{\"Filters\":[{\"Name\":\"endpoint-arn\",\"Values\":[\"" + target + "\"]}]}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ReplicationTasks[0].ReplicationTaskIdentifier", equalTo("lc-task"));

        dms("ModifyReplicationTask")
                .body("{\"ReplicationTaskArn\":\"" + task + "\",\"MigrationType\":\"full-load-and-cdc\","
                        + "\"TableMappings\":" + jsonString(mappings("app")) + "}")
        .when().post("/")
        .then().statusCode(200);

        describeTask("lc-task")
                .body("ReplicationTasks[0].MigrationType", equalTo("full-load-and-cdc"))
                .body("ReplicationTasks[0].TableMappings", equalTo(mappings("app")))
                .body("ReplicationTasks[0].Status", equalTo("ready"));

        dms("StartReplicationTask")
                .body("{\"ReplicationTaskArn\":\"" + task + "\",\"StartReplicationTaskType\":\"start-replication\"}")
        .when().post("/")
        .then().statusCode(200);

        describeTask("lc-task").body("ReplicationTasks[0].Status", equalTo("running"));

        dms("DeleteReplicationTask")
                .body("{\"ReplicationTaskArn\":\"" + task + "\"}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"));

        dms("StopReplicationTask")
                .body("{\"ReplicationTaskArn\":\"" + task + "\"}")
        .when().post("/")
        .then().statusCode(200);

        describeTask("lc-task").body("ReplicationTasks[0].Status", equalTo("stopped"));

        // The Terraform provider treats exactly this message as "already stopped".
        dms("StopReplicationTask")
                .body("{\"ReplicationTaskArn\":\"" + task + "\"}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"))
                .body("message", containsString("is currently not running"));

        dms("DeleteEndpoint")
                .body("{\"EndpointArn\":\"" + source + "\"}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"));

        dms("DeleteReplicationInstance")
                .body("{\"ReplicationInstanceArn\":\"" + instance + "\"}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidResourceStateFault"));

        dms("DeleteReplicationTask")
                .body("{\"ReplicationTaskArn\":\"" + task + "\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ReplicationTask.Status", equalTo("deleting"));

        dms("DescribeReplicationTasks")
                .body("{\"Filters\":[{\"Name\":\"replication-task-id\",\"Values\":[\"lc-task\"]}]}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));

        delete("DeleteEndpoint", "EndpointArn", source);
        delete("DeleteEndpoint", "EndpointArn", target);
        delete("DeleteReplicationInstance", "ReplicationInstanceArn", instance);
    }

    @Test
    void tagsAddAndRemoveOnEndpointInstanceAndTaskArns() {
        String source = createEndpoint("lc-tag-src", "source", "");
        String target = createEndpoint("lc-tag-tgt", "target", "");
        String instance = createInstance("lc-tag-rep");
        String task = dms("CreateReplicationTask")
                .body("{\"ReplicationTaskIdentifier\":\"lc-tag-task\",\"SourceEndpointArn\":\"" + source + "\","
                        + "\"TargetEndpointArn\":\"" + target + "\",\"ReplicationInstanceArn\":\"" + instance + "\","
                        + "\"MigrationType\":\"full-load\",\"TableMappings\":" + jsonString(mappings("%")) + "}")
        .when().post("/")
        .then()
                .statusCode(200)
                .extract().path("ReplicationTask.ReplicationTaskArn");

        for (String arn : new String[] {source, instance, task}) {
            dms("AddTagsToResource")
                    .body("{\"ResourceArn\":\"" + arn + "\",\"Tags\":[{\"Key\":\"keep\",\"Value\":\"" + arn + "\"},"
                            + "{\"Key\":\"drop\",\"Value\":\"x\"}]}")
            .when().post("/")
            .then().statusCode(200);
            dms("RemoveTagsFromResource")
                    .body("{\"ResourceArn\":\"" + arn + "\",\"TagKeys\":[\"drop\"]}")
            .when().post("/")
            .then().statusCode(200);
        }

        // Each ARN reads back only its own tags: a mutation routed to the wrong resource kind shows here.
        for (String arn : new String[] {source, instance, task}) {
            dms("ListTagsForResource")
                    .body("{\"ResourceArn\":\"" + arn + "\"}")
            .when().post("/")
            .then()
                    .statusCode(200)
                    .body("TagList", hasSize(1))
                    .body("TagList[0].Key", equalTo("keep"))
                    .body("TagList[0].Value", equalTo(arn));
        }
        dms("ListTagsForResource")
                .body("{\"ResourceArn\":\"" + target + "\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("TagList", hasSize(0));

        describeEndpoint("lc-tag-src").body("Endpoints[0].EndpointArn", equalTo(source));
        describeInstance("lc-tag-rep").body("ReplicationInstances[0].ReplicationInstanceArn", equalTo(instance));
        describeTask("lc-tag-task").body("ReplicationTasks[0].ReplicationTaskArn", equalTo(task));

        delete("DeleteReplicationTask", "ReplicationTaskArn", task);
        delete("DeleteEndpoint", "EndpointArn", source);
        delete("DeleteEndpoint", "EndpointArn", target);
        delete("DeleteReplicationInstance", "ReplicationInstanceArn", instance);
    }

    @Test
    void replicationTaskNamingUnknownEndpointIsNotFound() {
        dms("CreateReplicationTask")
                .body("{\"ReplicationTaskIdentifier\":\"lc-task-orphan\","
                        + "\"SourceEndpointArn\":\"arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":endpoint:NOPE\","
                        + "\"TargetEndpointArn\":\"arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":endpoint:NOPE2\","
                        + "\"ReplicationInstanceArn\":\"arn:aws:dms:us-east-1:" + ACCOUNT_ID + ":rep:NOPE\","
                        + "\"MigrationType\":\"full-load\",\"TableMappings\":\"{}\"}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
    }

    @Test
    void replicationTaskWithReversedEndpointsIsRejected() {
        String source = createEndpoint("lc-rev-src", "source", "");
        String target = createEndpoint("lc-rev-tgt", "target", "");
        String instance = createInstance("lc-rev-rep");
        dms("CreateReplicationTask")
                .body("{\"ReplicationTaskIdentifier\":\"lc-task-reversed\","
                        + "\"SourceEndpointArn\":\"" + target + "\","
                        + "\"TargetEndpointArn\":\"" + source + "\","
                        + "\"ReplicationInstanceArn\":\"" + instance + "\","
                        + "\"MigrationType\":\"full-load\",\"TableMappings\":" + jsonString(mappings("app")) + "}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"));
        dms("DescribeReplicationTasks")
                .body("{\"Filters\":[{\"Name\":\"replication-task-id\",\"Values\":[\"lc-task-reversed\"]}]}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
        delete("DeleteReplicationInstance", "ReplicationInstanceArn", instance);
        delete("DeleteEndpoint", "EndpointArn", source);
        delete("DeleteEndpoint", "EndpointArn", target);
    }

    @Test
    void replicationTaskWithSourceEndpointAsTargetIsRejected() {
        String source = createEndpoint("lc-src-as-tgt", "source", "");
        String instance = createInstance("lc-src-as-tgt-rep");
        dms("CreateReplicationTask")
                .body("{\"ReplicationTaskIdentifier\":\"lc-task-src-as-tgt\","
                        + "\"SourceEndpointArn\":\"" + source + "\","
                        + "\"TargetEndpointArn\":\"" + source + "\","
                        + "\"ReplicationInstanceArn\":\"" + instance + "\","
                        + "\"MigrationType\":\"full-load\",\"TableMappings\":" + jsonString(mappings("app")) + "}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidParameterValueException"))
                .body("message", containsString("TargetEndpointArn"));
        dms("DescribeReplicationTasks")
                .body("{\"Filters\":[{\"Name\":\"replication-task-id\",\"Values\":[\"lc-task-src-as-tgt\"]}]}")
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("ResourceNotFoundFault"));
        delete("DeleteReplicationInstance", "ReplicationInstanceArn", instance);
        delete("DeleteEndpoint", "EndpointArn", source);
    }

    private static String mappings(String schema) {
        return "{\"rules\":[{\"rule-type\":\"selection\",\"rule-id\":\"1\",\"rule-name\":\"1\","
                + "\"object-locator\":{\"schema-name\":\"" + schema + "\",\"table-name\":\"%\"},"
                + "\"rule-action\":\"include\"}]}";
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String createEndpoint(String identifier, String type, String extraMembers) {
        return dms("CreateEndpoint")
                .body("{\"EndpointIdentifier\":\"" + identifier + "\",\"EndpointType\":\"" + type + "\","
                        + "\"EngineName\":\"" + ("target".equals(type) ? "s3" : "mysql") + "\"" + extraMembers + "}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Endpoint.Password", nullValue())
                .extract().path("Endpoint.EndpointArn");
    }

    private static String createInstance(String identifier) {
        return dms("CreateReplicationInstance")
                .body("{\"ReplicationInstanceIdentifier\":\"" + identifier + "\","
                        + "\"ReplicationInstanceClass\":\"dms.t3.micro\"}")
        .when().post("/")
        .then()
                .statusCode(200)
                .extract().path("ReplicationInstance.ReplicationInstanceArn");
    }

    private static ValidatableResponse describeEndpoint(String identifier) {
        return describe("DescribeEndpoints", "endpoint-id", identifier);
    }

    private static ValidatableResponse describeInstance(String identifier) {
        return describe("DescribeReplicationInstances", "replication-instance-id", identifier);
    }

    private static ValidatableResponse describeTask(String identifier) {
        return describe("DescribeReplicationTasks", "replication-task-id", identifier);
    }

    private static ValidatableResponse describe(String action, String filter,
                                                                          String value) {
        return dms(action)
                .body("{\"Filters\":[{\"Name\":\"" + filter + "\",\"Values\":[\"" + value + "\"]}]}")
        .when().post("/")
        .then().statusCode(200);
    }

    private static void delete(String action, String member, String value) {
        dms(action)
                .body("{\"" + member + "\":\"" + value + "\"}")
        .when().post("/")
        .then().statusCode(200);
    }

    private static RequestSpecification dms(String action) {
        return given()
                .contentType(CONTENT_TYPE)
                .header("X-Amz-Target", TARGET_PREFIX + action)
                .header("Authorization", AUTH_HEADER);
    }
}
