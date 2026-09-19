package io.github.hectorvent.floci.services.glue;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * The Data Catalog connection lifecycle over the JSON 1.1 protocol, in the order Terraform's
 * aws_glue_connection drives it: create with tags, read on every refresh, update by full
 * redefinition, delete. Each test uses its own names so the suite can run in any order.
 */
@QuarkusTest
class GlueConnectionIntegrationTest {

    private static final String CONTENT_TYPE = "application/x-amz-json-1.1";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void createJdbcConnection(String name, String tagsJson) {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateConnection")
                .body("""
                        {
                          "ConnectionInput": {
                            "Name": "%s",
                            "Description": "orders database",
                            "ConnectionType": "JDBC",
                            "MatchCriteria": ["orders"],
                            "ConnectionProperties": {
                              "JDBC_CONNECTION_URL": "jdbc:postgresql://db.internal:5432/orders",
                              "USERNAME": "app",
                              "PASSWORD": "s3cret"
                            },
                            "PhysicalConnectionRequirements": {
                              "SubnetId": "subnet-0123456789abcdef0",
                              "SecurityGroupIdList": ["sg-0123456789abcdef0"],
                              "AvailabilityZone": "us-east-1a"
                            }
                          }%s
                        }
                        """.formatted(name, tagsJson))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("CreateConnectionStatus", equalTo("READY"));
    }

    @Test
    void createConnection_thenGetConnection_returnsTheDefinitionWithServiceOwnedFields() {
        String name = unique("orders");
        createJdbcConnection(name, "");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetConnection")
                .body("{\"Name\": \"%s\"}".formatted(name))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Connection.Name", equalTo(name))
                .body("Connection.ConnectionType", equalTo("JDBC"))
                .body("Connection.Description", equalTo("orders database"))
                .body("Connection.ConnectionProperties.PASSWORD", equalTo("s3cret"))
                .body("Connection.PhysicalConnectionRequirements.SubnetId", equalTo("subnet-0123456789abcdef0"))
                .body("Connection.Status", equalTo("READY"))
                .body("Connection.ConnectionSchemaVersion", equalTo(1))
                .body("Connection.CreationTime", notNullValue())
                .body("Connection.LastUpdatedTime", notNullValue());
    }

    @Test
    void getConnection_hidePassword_omitsThePasswordAndKeepsTheRest() {
        String name = unique("hidden");
        createJdbcConnection(name, "");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetConnection")
                .body("{\"Name\": \"%s\", \"HidePassword\": true}".formatted(name))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Connection.ConnectionProperties", not(hasKey("PASSWORD")))
                .body("Connection.ConnectionProperties.USERNAME", equalTo("app"));
    }

    @Test
    void getConnections_filterByType_returnsOnlyMatchingConnections() {
        String jdbc = unique("jdbc");
        createJdbcConnection(jdbc, "");
        String kafka = unique("kafka");
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateConnection")
                .body("""
                        {
                          "ConnectionInput": {
                            "Name": "%s",
                            "ConnectionType": "KAFKA",
                            "ConnectionProperties": {"KAFKA_BOOTSTRAP_SERVERS": "broker:9092"}
                          }
                        }
                        """.formatted(kafka))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetConnections")
                .body("{\"Filter\": {\"ConnectionType\": \"KAFKA\"}, \"MaxResults\": 100}")
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ConnectionList.findAll { it.ConnectionType == 'KAFKA' }.Name", hasItem(kafka))
                .body("ConnectionList.findAll { it.ConnectionType != 'KAFKA' }.size()", equalTo(0))
                .body("NextToken", nullValue());
    }

    @Test
    void updateConnection_redefinesTheConnection_droppingMembersLeftOut() {
        String name = unique("redefined");
        createJdbcConnection(name, "");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.UpdateConnection")
                .body("""
                        {
                          "Name": "%s",
                          "ConnectionInput": {
                            "Name": "%s",
                            "ConnectionType": "JDBC",
                            "ConnectionProperties": {
                              "JDBC_CONNECTION_URL": "jdbc:postgresql://db2.internal:5432/orders",
                              "SECRET_ID": "prod/orders"
                            }
                          }
                        }
                        """.formatted(name, name))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetConnection")
                .body("{\"Name\": \"%s\"}".formatted(name))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Connection.ConnectionProperties.JDBC_CONNECTION_URL", equalTo("jdbc:postgresql://db2.internal:5432/orders"))
                .body("Connection.ConnectionProperties.SECRET_ID", equalTo("prod/orders"))
                .body("Connection.ConnectionProperties", not(hasKey("PASSWORD")))
                .body("Connection", not(hasKey("Description")))
                .body("Connection", not(hasKey("PhysicalConnectionRequirements")));
    }

    @Test
    void createConnectionWithTags_tagsReadableThroughGlueAndResourceGroupsTagging() {
        String name = unique("tagged");
        String arn = "arn:aws:glue:us-east-1:000000000000:connection/" + name;
        createJdbcConnection(name, ",\n  \"Tags\": {\"env\": \"dev\"}");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.GetTags")
                .body("{\"ResourceArn\": \"%s\"}".formatted(arn))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Tags.env", equalTo("dev"));

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "ResourceGroupsTaggingAPI_20170126.GetResources")
                .body("{\"ResourceARNList\": [\"%s\"]}".formatted(arn))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("ResourceTagMappingList.size()", equalTo(1))
                .body("ResourceTagMappingList[0].ResourceARN", equalTo(arn))
                .body("ResourceTagMappingList[0].Tags[0].Key", equalTo("env"))
                .body("ResourceTagMappingList[0].Tags[0].Value", equalTo("dev"));
    }

    @Test
    void deleteConnection_removesIt_andASecondDeleteIsEntityNotFound() {
        String name = unique("deleted");
        createJdbcConnection(name, "");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.DeleteConnection")
                .body("{\"ConnectionName\": \"%s\"}".formatted(name))
        .when().post("/")
        .then().statusCode(200);

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.DeleteConnection")
                .body("{\"ConnectionName\": \"%s\"}".formatted(name))
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("EntityNotFoundException"));
    }

    @Test
    void batchDeleteConnection_reportsMissingNamesInErrors() {
        String name = unique("batch");
        createJdbcConnection(name, "");

        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.BatchDeleteConnection")
                .body("{\"ConnectionNameList\": [\"%s\", \"%s-absent\"]}".formatted(name, name))
        .when().post("/")
        .then()
                .statusCode(200)
                .body("Succeeded[0]", equalTo(name))
                .body("Errors.'%s-absent'.ErrorCode".formatted(name), equalTo("EntityNotFoundException"));
    }

    @Test
    void createConnection_unknownPropertyKey_isInvalidInput() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.CreateConnection")
                .body("""
                        {
                          "ConnectionInput": {
                            "Name": "%s",
                            "ConnectionType": "JDBC",
                            "ConnectionProperties": {"HOSTNAME": "db"}
                          }
                        }
                        """.formatted(unique("bad")))
        .when().post("/")
        .then()
                .statusCode(400)
                .body("__type", equalTo("InvalidInputException"));
    }

    @Test
    void testConnection_acceptsAnInlineDefinitionWithAnEmptyBody() {
        given().contentType(CONTENT_TYPE)
                .header("X-Amz-Target", "AWSGlue.TestConnection")
                .body("""
                        {
                          "TestConnectionInput": {
                            "ConnectionType": "JDBC",
                            "ConnectionProperties": {"JDBC_CONNECTION_URL": "jdbc:mysql://h:3306/d"}
                          }
                        }
                        """)
        .when().post("/")
        .then().statusCode(200);
    }
}
